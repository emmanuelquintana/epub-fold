package com.emmanuel.epubfold

import android.content.Context
import android.content.Intent
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Club de lectura con amigos (Firestore):
 *   clubs/{id}                 nombre, libro, miembros, código
 *   clubs/{id}/progress/{uid}  progreso de cada miembro
 *   clubs/{id}/comments/{id}   comentarios por capítulo (ocultos si aún no llegas)
 *   clubCodes/{código}         para unirse con un código de 6 letras
 */
object Clubs {

    data class Club(
        val id: String, val name: String, val bookTitle: String, val bookAuthor: String,
        val bookKey: String, val code: String, val owner: String, val members: List<String>,
        val names: Map<String, String>,
    )

    data class Progress(val uid: String, val name: String, val progress: Double, val chapter: Int, val updatedAt: Long)
    data class Comment(val id: String, val uid: String, val name: String, val chapter: Int, val chapterTitle: String, val text: String, val created: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun db() = FirebaseFirestore.getInstance()
    fun uid(context: Context) = CloudSync.user(context)?.uid

    fun myName(): String {
        val u = FirebaseAuth.getInstance().currentUser ?: return "Lector"
        return u.displayName?.takeIf { it.isNotBlank() } ?: u.email?.substringBefore('@') ?: "Lector"
    }

    private fun parse(d: com.google.firebase.firestore.DocumentSnapshot): Club {
        @Suppress("UNCHECKED_CAST")
        val names = (d.get("names") as? Map<String, String>) ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val members = (d.get("members") as? List<String>) ?: emptyList()
        return Club(
            d.id, d.getString("name") ?: "Club", d.getString("bookTitle") ?: "", d.getString("bookAuthor") ?: "",
            d.getString("bookKey") ?: "", d.getString("code") ?: "", d.getString("owner") ?: "", members, names,
        )
    }

    suspend fun myClubs(context: Context): List<Club> {
        val u = uid(context) ?: return emptyList()
        return db().collection("clubs").whereArrayContains("members", u).get().await().documents.map { parse(it) }
            .sortedBy { it.name.lowercase() }
    }

    suspend fun get(id: String): Club = parse(db().collection("clubs").document(id).get().await())

    private fun newCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars.random() }.joinToString("")
    }

    suspend fun create(context: Context, name: String, book: BookEntry): String {
        val u = uid(context) ?: throw IllegalStateException("Inicia sesión primero")
        val ref = db().collection("clubs").document()
        val code = newCode()
        val batch = db().batch()
        batch.set(ref, mapOf(
            "name" to name, "bookTitle" to book.title, "bookAuthor" to book.author, "bookKey" to book.key,
            "owner" to u, "members" to listOf(u), "names" to mapOf(u to myName()), "code" to code,
            "created" to System.currentTimeMillis(),
        ))
        batch.set(db().collection("clubCodes").document(code), mapOf("club" to ref.id))
        batch.commit().await()
        pushProgressNow(context, book, ref.id)
        return ref.id
    }

    suspend fun join(context: Context, rawCode: String): String {
        val u = uid(context) ?: throw IllegalStateException("Inicia sesión primero")
        val code = rawCode.trim().uppercase()
        val doc = db().collection("clubCodes").document(code).get().await()
        val id = doc.getString("club") ?: throw IllegalArgumentException("No existe ningún club con ese código")
        db().collection("clubs").document(id)
            .update(mapOf("members" to FieldValue.arrayUnion(u), "names.$u" to myName())).await()
        // Si ya tienes el libro, se publica tu progreso
        val club = get(id)
        Library(context).findByKey(club.bookKey)?.let { pushProgressNow(context, it, id) }
        return id
    }

    suspend fun leave(context: Context, id: String) {
        val u = uid(context) ?: return
        db().collection("clubs").document(id).collection("progress").document(u).delete().await()
        db().collection("clubs").document(id).update("members", FieldValue.arrayRemove(u)).await()
    }

    suspend fun progress(id: String): List<Progress> =
        db().collection("clubs").document(id).collection("progress").get().await().documents.map {
            Progress(it.id, it.getString("name") ?: "", it.getDouble("progress") ?: 0.0,
                (it.getLong("chapter") ?: 0L).toInt(), it.getLong("updatedAt") ?: 0L)
        }.sortedByDescending { it.progress }

    suspend fun comments(id: String): List<Comment> =
        db().collection("clubs").document(id).collection("comments").orderBy("created", Query.Direction.ASCENDING)
            .get().await().documents.map {
                Comment(it.id, it.getString("uid") ?: "", it.getString("name") ?: "", (it.getLong("chapter") ?: 0L).toInt(),
                    it.getString("chapterTitle") ?: "", it.getString("text") ?: "", it.getLong("created") ?: 0L)
            }

    suspend fun postComment(context: Context, id: String, chapter: Int, chapterTitle: String, text: String) {
        val u = uid(context) ?: throw IllegalStateException("Inicia sesión primero")
        db().collection("clubs").document(id).collection("comments").add(mapOf(
            "uid" to u, "name" to myName(), "chapter" to chapter, "chapterTitle" to chapterTitle,
            "text" to text.trim().take(2000), "created" to System.currentTimeMillis(),
        )).await()
    }

    suspend fun deleteComment(id: String, commentId: String) {
        db().collection("clubs").document(id).collection("comments").document(commentId).delete().await()
    }

    private suspend fun pushProgressNow(context: Context, book: BookEntry, clubId: String) {
        val u = uid(context) ?: return
        db().collection("clubs").document(clubId).collection("progress").document(u).set(mapOf(
            "name" to myName(), "progress" to book.progress.toDouble(), "chapter" to book.chapter,
            "updatedAt" to System.currentTimeMillis(),
        )).await()
    }

    /** Al salir de un libro: publica tu progreso en los clubes que leen ese libro. */
    fun pushProgress(context: Context, bookId: String) {
        val app = context.applicationContext
        if (uid(app) == null) return
        val sp = app.getSharedPreferences("clubs", Context.MODE_PRIVATE)
        val book = Library(app).get(bookId) ?: return
        // Sólo si ese libro está en algún club (se recuerda para no consultar siempre)
        val known = sp.getStringSet("bookKeys", null)
        if (known != null && book.key !in known) return
        scope.launch {
            runCatching {
                withTimeout(20_000) {
                    val clubs = myClubs(app)
                    sp.edit().putStringSet("bookKeys", clubs.map { it.bookKey }.toSet()).apply()
                    clubs.filter { it.bookKey == book.key }.forEach { pushProgressNow(app, book, it.id) }
                }
            }
        }
    }

    fun rememberKeys(context: Context, clubs: List<Club>) {
        context.getSharedPreferences("clubs", Context.MODE_PRIVATE).edit()
            .putStringSet("bookKeys", clubs.map { it.bookKey }.toSet()).apply()
    }
}

/** Lista de tus clubes: crear uno o unirse con un código. */
class ClubsActivity : GroupedActivity() {

    override val screenTitle = "Clubes de lectura"
    private var clubs: List<Clubs.Club>? = null

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        if (Clubs.uid(this) == null) return
        lifecycleScope.launch {
            val r = runCatching { Clubs.myClubs(this@ClubsActivity) }
            clubs = r.getOrNull() ?: emptyList()
            r.getOrNull()?.let { Clubs.rememberKeys(this@ClubsActivity, it) }
            r.exceptionOrNull()?.let { toast("No se pudieron cargar: ${it.message}") }
            rebuild()
        }
    }

    override fun build() {
        if (!CloudSync.available(this) || Clubs.uid(this) == null) {
            group().rows(
                paragraph("Lee el mismo libro con amigos: veréis el progreso de cada uno y podréis comentar " +
                    "cada capítulo sin destripes (los comentarios de capítulos a los que aún no llegas se ocultan)."),
                row("Iniciar sesión para usar los clubes") { startActivity(Intent(this, AccountActivity::class.java)) },
            )
            return
        }
        caption("Tus clubes")
        val list = group()
        when {
            clubs == null -> list.addView(paragraph("Cargando…"))
            clubs!!.isEmpty() -> list.addView(paragraph("Aún no estás en ningún club. Crea uno y comparte el código con tus amigos."))
            else -> clubs!!.forEach { c ->
                list.rows(row(c.name, "${c.bookTitle} · ${c.members.size} ${if (c.members.size == 1) "miembro" else "miembros"}", trailing = "›") {
                    startActivity(Intent(this, ClubActivity::class.java).putExtra("club", c.id))
                })
            }
        }
        spacer(22)
        group().rows(
            row("Crear un club…", "Elige un libro de tu biblioteca") { create() },
            row("Unirme con un código…") { join() },
        )
        footnote("Cada miembro necesita su propio EPUB del libro: los libros no se comparten, sólo el progreso y los comentarios.")
    }

    private fun create() {
        val books = Library(this).all()
        if (books.isEmpty()) { toast("Añade primero el libro a tu biblioteca"); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Qué libro vais a leer?")
            .setItems(books.map { it.title }.toTypedArray()) { _, which ->
                val book = books[which]
                val f = field("Nombre del club", "Club de «${book.title.take(30)}»")
                MaterialAlertDialogBuilder(this)
                    .setTitle("Nombre del club")
                    .setView(FrameLayout(this).apply { addView(f) })
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Crear") { _, _ ->
                        lifecycleScope.launch {
                            val r = runCatching { Clubs.create(this@ClubsActivity, f.text.toString().trim().ifEmpty { "Club" }, book) }
                            r.onSuccess { id -> startActivity(Intent(this@ClubsActivity, ClubActivity::class.java).putExtra("club", id)) }
                                .onFailure { toast("No se pudo crear: ${it.message}") }
                        }
                    }
                    .show()
            }
            .show()
    }

    private fun join() {
        val f = field("Código de 6 letras", type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        MaterialAlertDialogBuilder(this)
            .setTitle("Unirme a un club")
            .setView(FrameLayout(this).apply { addView(f) })
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Unirme") { _, _ ->
                lifecycleScope.launch {
                    val r = runCatching { Clubs.join(this@ClubsActivity, f.text.toString()) }
                    r.onSuccess { id -> startActivity(Intent(this@ClubsActivity, ClubActivity::class.java).putExtra("club", id)) }
                        .onFailure { toast(it.message ?: "No se pudo entrar") }
                }
            }
            .show()
    }
}

/** Un club: progreso de cada miembro y comentarios por capítulo. */
class ClubActivity : GroupedActivity() {

    override val screenTitle = "Club"
    private lateinit var clubId: String
    private var club: Clubs.Club? = null
    private var progress: List<Clubs.Progress> = emptyList()
    private var comments: List<Clubs.Comment> = emptyList()
    private val revealed = HashSet<String>()
    private var titles: (Int) -> String = { "Capítulo ${it + 1}" }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        clubId = intent.getStringExtra("club") ?: ""
        super.onCreate(savedInstanceState)
        load()
    }

    private fun localBook() = club?.let { Library(this).findByKey(it.bookKey) }

    private fun load() {
        lifecycleScope.launch {
            val r = runCatching {
                val c = Clubs.get(clubId)
                Triple(c, Clubs.progress(clubId), Clubs.comments(clubId))
            }
            r.onSuccess { (c, p, cm) ->
                club = c; progress = p; comments = cm
                // Títulos de capítulo del EPUB local, si lo tienes
                localBook()?.takeIf { it.format == BookFormat.EPUB }?.let { b ->
                    val names = withContext(Dispatchers.IO) {
                        runCatching { EpubBook(File(b.path)).use { e -> e.spine.indices.map { e.chapterTitle(it) } } }.getOrNull()
                    }
                    if (names != null) titles = { i -> names.getOrElse(i) { "Capítulo ${i + 1}" } }
                }
                rebuild()
            }.onFailure { toast("No se pudo abrir el club: ${it.message}") }
        }
    }

    override fun build() {
        val c = club ?: run {
            group().addView(paragraph("Cargando…"))
            return
        }
        val me = Clubs.uid(this)
        val mine = localBook()
        val myChapter = mine?.chapter ?: -1

        group().rows(
            row(c.name, "${c.bookTitle}${if (c.bookAuthor.isNotEmpty()) " · ${c.bookAuthor}" else ""}", plain = true),
            row("Invitar amigos · código ${c.code}", "Comparte el código para que se unan") {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "¡Únete a mi club de lectura de «${c.bookTitle}» en EpubFold! " +
                        "Ve a Más › Clubes de lectura › Unirme con un código: ${c.code}")
                }, "Invitar"))
            },
            if (mine != null) row("Abrir el libro") { startActivity(LibraryActivity.readerIntent(this, mine)) }
            else row("No tienes este libro", "Añade el EPUB «${c.bookTitle}» a tu biblioteca para leer con el club", plain = true),
        )

        spacer(22)
        caption("Progreso")
        val pg = group()
        if (progress.isEmpty()) pg.addView(paragraph("Nadie ha empezado todavía."))
        progress.forEach { p ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpi(16), dpi(12), dpi(16), dpi(12))
            }
            box.addView(row(
                (if (p.uid == me) "Tú" else p.name.ifEmpty { "Lector" }),
                titles(p.chapter), trailing = "${(p.progress * 100).toInt()} %", plain = true,
            ).apply { setPadding(0, 0, 0, dpi(6)) })
            box.addView(LinearProgressIndicator(this).apply {
                max = 1000
                progress = (p.progress * 1000).toInt()
                setIndicatorColor(color(R.color.accent))
                trackColor = color(R.color.fill)
                trackCornerRadius = dpi(2)
            })
            if (pg.childCount > 0) pg.addView(hairline(this, dpi(16)))
            pg.addView(box)
        }

        spacer(22)
        caption("Comentarios")
        val cm = group()
        cm.rows(row("Escribir un comentario…", if (mine != null) "Sobre: ${titles(myChapter.coerceAtLeast(0))}" else null) { compose(mine) })
        if (comments.isEmpty()) cm.rows(paragraph("Sin comentarios todavía."))
        comments.forEach { k ->
            val hidden = mine != null && k.chapter > myChapter && k.uid != me && k.id !in revealed
            val who = if (k.uid == me) "Tú" else k.name
            val chapterName = k.chapterTitle.ifEmpty { titles(k.chapter) }
            cm.rows(
                if (hidden) row("🔒 Comentario de $who", "$chapterName · aún no llegas ahí. Toca para verlo igualmente.", plain = true) {
                    revealed += k.id
                    rebuild()
                } else row(k.text, "$who · $chapterName · " + android.text.format.DateUtils.getRelativeTimeSpanString(k.created),
                    plain = true, onLongClick = if (k.uid == me) ({ deleteComment(k) }) else null)
            )
        }

        spacer(22)
        group().rows(row("Salir del club", destructive = true) {
            MaterialAlertDialogBuilder(this)
                .setTitle("¿Salir de «${c.name}»?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Salir") { _, _ ->
                    lifecycleScope.launch {
                        runCatching { Clubs.leave(this@ClubActivity, clubId) }
                            .onSuccess { finish() }.onFailure { toast("Error: ${it.message}") }
                    }
                }.show()
        })
    }

    private fun compose(mine: BookEntry?) {
        val f = EditText(this).apply {
            hint = "¿Qué te ha parecido?"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dpi(20), dpi(8), dpi(20), 0)
            }
        }
        val ch = (mine?.chapter ?: 0).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("Comentario sobre: ${titles(ch)}")
            .setView(FrameLayout(this).apply { addView(f) })
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Publicar") { _, _ ->
                val text = f.text.toString()
                if (text.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    runCatching { Clubs.postComment(this@ClubActivity, clubId, ch, titles(ch), text) }
                        .onSuccess { load() }.onFailure { toast("No se pudo publicar: ${it.message}") }
                }
            }
            .show()
    }

    private fun deleteComment(k: Clubs.Comment) {
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Borrar tu comentario?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Borrar") { _, _ ->
                lifecycleScope.launch {
                    runCatching { Clubs.deleteComment(clubId, k.id) }.onSuccess { load() }.onFailure { toast("Error: ${it.message}") }
                }
            }.show()
    }

}
