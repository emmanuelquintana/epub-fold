package com.emmanuel.epubfold

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Lleva tus subrayados y notas a Obsidian (archivos Markdown en tu bóveda),
 * Readwise y Notion. Se sincroniza solo al salir de un libro.
 */
object Integrations {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false

    fun prefs(context: Context) = context.getSharedPreferences("integrations", Context.MODE_PRIVATE)

    fun anyEnabled(context: Context): Boolean {
        val p = prefs(context)
        return p.getString("obsidian", null) != null || !p.getString("readwise", null).isNullOrBlank() ||
            (!p.getString("notionToken", null).isNullOrBlank() && !p.getString("notionDb", null).isNullOrBlank())
    }

    /** Sincroniza en segundo plano (como mucho cada 2 minutos). */
    fun autoSync(context: Context) {
        val app = context.applicationContext
        if (!anyEnabled(app) || running) return
        if (System.currentTimeMillis() - prefs(app).getLong("lastAuto", 0L) < 2 * 60_000L) return
        prefs(app).edit().putLong("lastAuto", System.currentTimeMillis()).apply()
        scope.launch { runCatching { syncAll(app) } }
    }

    /** Devuelve un resumen legible de lo que se hizo. */
    fun syncAll(context: Context): String {
        if (running) return "Ya se está sincronizando"
        running = true
        try {
            val out = ArrayList<String>()
            val p = prefs(context)
            p.getString("obsidian", null)?.let { out += "Obsidian: " + runCatching { obsidian(context, Uri.parse(it)) }.fold({ "$it libros" }, { "error (${it.message})" }) }
            p.getString("readwise", null)?.takeIf { it.isNotBlank() }?.let { tok ->
                out += "Readwise: " + runCatching { readwise(context, tok) }.fold({ "$it subrayados nuevos" }, { "error (${it.message})" })
            }
            val nt = p.getString("notionToken", null)
            val nd = p.getString("notionDb", null)
            if (!nt.isNullOrBlank() && !nd.isNullOrBlank()) {
                out += "Notion: " + runCatching { notion(context, nt, nd) }.fold({ "$it subrayados nuevos" }, { "error (${it.message})" })
            }
            p.edit().putLong("lastSync", System.currentTimeMillis()).apply()
            return out.joinToString("\n").ifEmpty { "No hay ningún servicio conectado" }
        } finally {
            running = false
        }
    }

    private fun booksWithNotes(context: Context): List<Pair<BookEntry, Annotations>> =
        Library(context).all().map { it to Annotations(context, it.id) }
            .filter { (_, a) -> a.highlights.isNotEmpty() || a.notebook.isNotBlank() }

    fun markdown(b: BookEntry, a: Annotations, chapterTitle: (Int) -> String = { "Capítulo ${it + 1}" }): String {
        val front = "---\ntitle: \"${b.title.replace("\"", "'")}\"\nauthor: \"${b.author.replace("\"", "'")}\"\n" +
            (if (b.series.isNotEmpty()) "series: \"${b.series.replace("\"", "'")}\"\n" else "") +
            "source: EpubFold\ntags: [libro]\n---\n\n"
        return front + a.toMarkdown(b.title, b.author, chapterTitle)
    }

    /** Todas las notas de todos los libros en un solo Markdown. */
    fun allMarkdown(context: Context): String =
        booksWithNotes(context).joinToString("\n\n---\n\n") { (b, a) -> a.toMarkdown(b.title, b.author) { "Capítulo ${it + 1}" } }

    // ---------------------------------------------------------------- Obsidian

    private fun obsidian(context: Context, tree: Uri): Int {
        val dir = DocumentFile.fromTreeUri(context, tree) ?: throw IllegalStateException("No se puede abrir la carpeta")
        val since = prefs(context).getLong("obsidianSince", 0L)
        var n = 0
        booksWithNotes(context).forEach { (b, a) ->
            if (a.updatedAt <= since) return@forEach
            val name = b.title.replace(Regex("[\\\\/:*?\"<>|#^\\[\\]]"), " ").trim().take(80).ifEmpty { "Libro" } + ".md"
            val file = dir.findFile(name) ?: dir.createFile("application/octet-stream", name)
                ?: throw IllegalStateException("No se pudo crear $name")
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(markdown(b, a).toByteArray()) }
            n++
        }
        prefs(context).edit().putLong("obsidianSince", System.currentTimeMillis()).apply()
        return n
    }

    // ---------------------------------------------------------------- Readwise

    private fun iso(t: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))

    private fun sentSet(context: Context, key: String): MutableSet<String> =
        HashSet(prefs(context).getStringSet(key, emptySet()) ?: emptySet())

    private fun http(method: String, url: String, headers: Map<String, String>, body: JSONObject?): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code == 401 || code == 403) throw IllegalStateException("token no válido o sin permiso")
            if (code !in 200..299) throw IllegalStateException("HTTP $code ${text.take(160)}")
            return runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        } finally {
            conn.disconnect()
        }
    }

    private fun readwise(context: Context, token: String): Int {
        val sent = sentSet(context, "readwiseSent")
        val batch = JSONArray()
        val ids = ArrayList<String>()
        booksWithNotes(context).forEach { (b, a) ->
            a.highlights.filter { it.id !in sent }.forEach { h ->
                batch.put(JSONObject().apply {
                    put("text", h.text.trim().take(8000))
                    put("title", b.title)
                    if (b.author.isNotEmpty()) put("author", b.author)
                    put("source_type", "epubfold")
                    put("category", "books")
                    if (h.note.isNotBlank()) put("note", h.note.trim())
                    put("location", h.chapter * 100000 + h.start)
                    put("location_type", "order")
                    put("highlighted_at", iso(h.created))
                })
                ids += h.id
            }
        }
        if (ids.isEmpty()) return 0
        // De 100 en 100 para no mandar peticiones enormes
        for (i in 0 until batch.length() step 100) {
            val part = JSONArray()
            for (j in i until minOf(i + 100, batch.length())) part.put(batch.get(j))
            http("POST", "https://readwise.io/api/v2/highlights/", mapOf("Authorization" to "Token $token"),
                JSONObject().put("highlights", part))
            sent += ids.subList(i, minOf(i + 100, ids.size))
            prefs(context).edit().putStringSet("readwiseSent", sent).apply()
        }
        return ids.size
    }

    // ---------------------------------------------------------------- Notion

    private fun notion(context: Context, token: String, rawDb: String): Int {
        val db = notionId(rawDb)
        val headers = mapOf("Authorization" to "Bearer $token", "Notion-Version" to "2022-06-28")
        // Nombre de la columna de título (cada base de datos puede llamarla distinto)
        val schema = http("GET", "https://api.notion.com/v1/databases/$db", headers, null)
        val props = schema.optJSONObject("properties") ?: JSONObject()
        val titleProp = props.keys().asSequence().firstOrNull { props.getJSONObject(it).optString("type") == "title" } ?: "Name"
        val pages = JSONObject(prefs(context).getString("notionPages", "{}") ?: "{}")
        val sent = sentSet(context, "notionSent")
        var n = 0
        booksWithNotes(context).forEach { (b, a) ->
            val fresh = a.highlights.filter { it.id !in sent }
            if (fresh.isEmpty()) return@forEach
            var page = pages.optString(b.key)
            if (page.isEmpty()) {
                val created = http("POST", "https://api.notion.com/v1/pages", headers, JSONObject()
                    .put("parent", JSONObject().put("database_id", db))
                    .put("properties", JSONObject().put(titleProp, JSONObject().put("title",
                        JSONArray().put(JSONObject().put("text", JSONObject().put("content", b.title.take(200))))))))
                page = created.getString("id")
                pages.put(b.key, page)
                prefs(context).edit().putString("notionPages", pages.toString()).apply()
            }
            fun rich(t: String) = JSONArray().put(JSONObject().put("type", "text").put("text", JSONObject().put("content", t.take(1990))))
            val blocks = JSONArray()
            fresh.forEach { h ->
                blocks.put(JSONObject().put("object", "block").put("type", "quote")
                    .put("quote", JSONObject().put("rich_text", rich(h.text.trim()))))
                if (h.note.isNotBlank()) blocks.put(JSONObject().put("object", "block").put("type", "paragraph")
                    .put("paragraph", JSONObject().put("rich_text", rich("Nota: " + h.note.trim()))))
            }
            for (i in 0 until blocks.length() step 90) {
                val part = JSONArray()
                for (j in i until minOf(i + 90, blocks.length())) part.put(blocks.get(j))
                http("PATCH", "https://api.notion.com/v1/blocks/$page/children", headers, JSONObject().put("children", part))
            }
            sent += fresh.map { it.id }
            prefs(context).edit().putStringSet("notionSent", sent).apply()
            n += fresh.size
        }
        return n
    }

    /** Acepta el ID o la URL completa de la base de datos. */
    fun notionId(raw: String): String {
        val hex = Regex("[0-9a-fA-F]{32}").find(raw.replace("-", ""))?.value ?: return raw.trim()
        return hex
    }
}

/** Pantalla «Exportar y sincronizar notas». */
class IntegrationsActivity : GroupedActivity() {

    override val screenTitle = "Exportar notas"

    private val pickVault = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        Integrations.prefs(this).edit().putString("obsidian", uri.toString()).putLong("obsidianSince", 0L).apply()
        rebuild()
        syncNow()
    }

    private val exportAll = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { it.write(Integrations.allMarkdown(this@IntegrationsActivity).toByteArray()) }
                }
            }
            toast(if (r.isSuccess) "Notas exportadas" else "Error: ${r.exceptionOrNull()?.message}")
        }
    }

    override fun build() {
        val p = Integrations.prefs(this)

        caption("Obsidian")
        val obs = group()
        val vault = p.getString("obsidian", null)
        if (vault == null) {
            obs.rows(row("Elegir carpeta de la bóveda…", "Se crea un archivo .md por libro y se actualiza solo") { pickVault.launch(null) })
        } else {
            val name = DocumentFile.fromTreeUri(this, Uri.parse(vault))?.name ?: vault
            obs.rows(
                row("Carpeta: $name", "Un .md por libro con subrayados, notas y cuaderno", plain = true),
                row("Cambiar carpeta…") { pickVault.launch(null) },
                row("Desconectar", destructive = true) {
                    p.edit().remove("obsidian").apply()
                    rebuild()
                },
            )
        }

        spacer(22)
        caption("Readwise")
        val rw = group()
        rw.rows(tokenRow("Token de Readwise", "readwise", "Cópialo de readwise.io/access_token"))

        spacer(22)
        caption("Notion")
        val nt = group()
        nt.rows(
            tokenRow("Token de la integración", "notionToken", "Crea una integración en notion.so/my-integrations"),
            tokenRow("Base de datos (URL o ID)", "notionDb", "Compártela con tu integración (⋯ › Conexiones)"),
        )
        footnote("En Notion se crea una página por libro y se le añaden los subrayados como citas.")

        spacer(22)
        val actions = group()
        actions.rows(
            row("Sincronizar ahora", lastSync()) { syncNow() },
            row("Exportar todo en un Markdown…", "Para guardarlo o abrirlo en cualquier app") {
                exportAll.launch("EpubFold-notas.md")
            },
        )
        footnote("Se sincroniza sola al salir de un libro. Sólo se envían los subrayados nuevos.")
    }

    private fun lastSync(): String {
        val t = Integrations.prefs(this).getLong("lastSync", 0L)
        return if (t == 0L) "Nunca" else "Última: " + android.text.format.DateUtils.getRelativeTimeSpanString(t)
    }

    private fun tokenRow(label: String, key: String, help: String): android.view.View {
        val p = Integrations.prefs(this)
        val value = p.getString(key, null).orEmpty()
        val shown = if (value.isEmpty()) "Sin configurar" else if (key == "notionDb") Integrations.notionId(value) else "••••" + value.takeLast(4)
        return row(label, "$shown · $help") {
            val f = field(label, value, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
            val box = android.widget.FrameLayout(this).apply { addView(f) }
            MaterialAlertDialogBuilder(this)
                .setTitle(label)
                .setView(box)
                .setNegativeButton("Cancelar", null)
                .setNeutralButton("Borrar") { _, _ -> p.edit().remove(key).apply(); rebuild() }
                .setPositiveButton("Guardar") { _, _ ->
                    p.edit().putString(key, f.text.toString().trim()).apply()
                    rebuild()
                }
                .show()
        }
    }

    private fun syncNow() {
        toast("Sincronizando…")
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) { runCatching { Integrations.syncAll(this@IntegrationsActivity) }.getOrElse { "Error: ${it.message}" } }
            MaterialAlertDialogBuilder(this@IntegrationsActivity).setTitle("Sincronización").setMessage(msg)
                .setPositiveButton("OK", null).show()
            rebuild()
        }
    }
}
