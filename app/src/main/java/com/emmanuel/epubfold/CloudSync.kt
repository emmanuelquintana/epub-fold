package com.emmanuel.epubfold

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Cuentas (Firebase Auth, email + contraseña) y sincronización del progreso,
 * marcadores, notas y estadísticas en Firestore:
 *
 *   users/{uid}/books/{clave-del-libro}
 *   users/{uid}/meta/stats
 *
 * Los EPUB no se suben: al importar el mismo libro en otro dispositivo
 * (mismo título y autor) se recupera todo lo demás.
 */
object CloudSync {

    data class SyncResult(val pushed: Int, val pulled: Int, val cloudOnly: List<String>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun available(context: Context): Boolean =
        BuildConfig.HAS_FIREBASE && runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)

    fun user(context: Context): FirebaseUser? =
        if (available(context)) FirebaseAuth.getInstance().currentUser else null

    suspend fun signIn(email: String, password: String) {
        FirebaseAuth.getInstance().signInWithEmailAndPassword(email.trim(), password).await()
    }

    suspend fun signUp(email: String, password: String) {
        FirebaseAuth.getInstance().createUserWithEmailAndPassword(email.trim(), password).await()
    }

    suspend fun resetPassword(email: String) {
        FirebaseAuth.getInstance().sendPasswordResetEmail(email.trim()).await()
    }

    fun signOut() {
        FirebaseAuth.getInstance().signOut()
    }

    private fun userDoc(uid: String) = FirebaseFirestore.getInstance().collection("users").document(uid)

    /** Sube en segundo plano un libro tras leer (si hay sesión). */
    fun schedulePush(context: Context, bookId: String) {
        val u = user(context) ?: return
        val app = context.applicationContext
        scope.launch {
            runCatching {
                withTimeout(20_000) {
                    pushBook(app, u.uid, bookId)
                    pushStats(app, u.uid)
                }
            }
        }
    }

    private suspend fun pushBook(context: Context, uid: String, bookId: String) {
        val b = Library(context).get(bookId) ?: return
        userDoc(uid).collection("books").document(b.key).set(toMap(context, b)).await()
    }

    private fun toMap(context: Context, b: BookEntry): Map<String, Any> {
        val notes = Annotations(context, b.id)
        return mapOf(
            "title" to b.title,
            "author" to b.author,
            "chapter" to b.chapter,
            "offset" to b.charOffset,
            "progress" to b.progress.toDouble(),
            "shelf" to b.shelf,
            "finishedAt" to b.finishedAt,
            "updatedAt" to b.updatedAt,
            "lastOpened" to b.lastOpened,
            "annotations" to notes.toJson().toString(),
            "annotationsUpdatedAt" to notes.updatedAt,
        )
    }

    private suspend fun pushStats(context: Context, uid: String) {
        val stats = ReadingStats(context)
        userDoc(uid).collection("meta").document("stats")
            .set(mapOf("days" to stats.toJson().toString()), SetOptions.merge()).await()
    }

    /** Sincronización completa en ambos sentidos. */
    suspend fun syncAll(context: Context): SyncResult {
        val u = user(context) ?: throw IllegalStateException("Inicia sesión primero")
        val library = Library(context)
        var pulled = 0
        var pushed = 0
        val cloudOnly = ArrayList<String>()
        withTimeout(60_000) {
            val remote = userDoc(u.uid).collection("books").get().await()
            val remoteKeys = HashSet<String>()
            for (doc in remote.documents) {
                remoteKeys += doc.id
                val local = library.findByKey(doc.id)
                if (local == null) {
                    cloudOnly += (doc.getString("title") ?: doc.id)
                    continue
                }
                val o = JSONObject().apply {
                    put("chapter", (doc.getLong("chapter") ?: 0L).toInt())
                    put("offset", (doc.getLong("offset") ?: 0L).toInt())
                    put("progress", doc.getDouble("progress") ?: 0.0)
                    put("shelf", doc.getString("shelf") ?: "")
                    put("finishedAt", doc.getLong("finishedAt") ?: 0L)
                    put("updatedAt", doc.getLong("updatedAt") ?: 0L)
                    put("lastOpened", doc.getLong("lastOpened") ?: 0L)
                    put("annotations", doc.getString("annotations") ?: "{}")
                }
                if (Backup.mergeBook(context, local, o)) pulled++
                // Si lo local es más nuevo, lo subimos
                val fresh = library.get(local.id) ?: continue
                val notes = Annotations(context, fresh.id)
                if (fresh.updatedAt > (doc.getLong("updatedAt") ?: 0L) ||
                    notes.updatedAt > (doc.getLong("annotationsUpdatedAt") ?: 0L)
                ) {
                    userDoc(u.uid).collection("books").document(fresh.key).set(toMap(context, fresh)).await()
                    pushed++
                }
            }
            // Libros que aún no están en la nube
            for (b in library.all()) {
                if (b.key !in remoteKeys) {
                    userDoc(u.uid).collection("books").document(b.key).set(toMap(context, b)).await()
                    pushed++
                }
            }
            // Estadísticas: máximo por día
            val statsDoc = userDoc(u.uid).collection("meta").document("stats").get().await()
            statsDoc.getString("days")?.let { runCatching { ReadingStats(context).mergeFrom(JSONObject(it)) } }
            pushStats(context, u.uid)
        }
        ReaderPrefs(context).lastSync = System.currentTimeMillis()
        return SyncResult(pushed, pulled, cloudOnly)
    }

    /** Tras importar un libro, recupera su progreso de la nube si existe. */
    fun pullOne(context: Context, bookId: String, done: () -> Unit) {
        val u = user(context) ?: return
        val app = context.applicationContext
        scope.launch {
            runCatching {
                withTimeout(15_000) {
                    val b = Library(app).get(bookId) ?: return@withTimeout
                    val doc = userDoc(u.uid).collection("books").document(b.key).get().await()
                    if (doc.exists()) {
                        val o = JSONObject().apply {
                            put("chapter", (doc.getLong("chapter") ?: 0L).toInt())
                            put("offset", (doc.getLong("offset") ?: 0L).toInt())
                            put("progress", doc.getDouble("progress") ?: 0.0)
                            put("shelf", doc.getString("shelf") ?: "")
                            put("finishedAt", doc.getLong("finishedAt") ?: 0L)
                            put("updatedAt", doc.getLong("updatedAt") ?: 0L)
                            put("lastOpened", doc.getLong("lastOpened") ?: 0L)
                            put("annotations", doc.getString("annotations") ?: "{}")
                        }
                        if (Backup.mergeBook(app, b, o)) kotlinx.coroutines.withContext(Dispatchers.Main) { done() }
                    }
                }
            }
        }
    }
}
