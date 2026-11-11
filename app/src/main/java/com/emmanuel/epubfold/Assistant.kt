package com.emmanuel.epubfold

import android.content.Context
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import org.json.JSONArray
import org.json.JSONObject

/**
 * Asistente de lectura con Claude (API de Anthropic). Usa la clave del propio usuario,
 * guardada sólo en este teléfono, y envía únicamente el texto leído hasta la página actual
 * para no revelar nada de lo que viene después.
 */
object Assistant {

    /** Modelos que puede elegir el usuario (el primero es el recomendado). */
    val MODELS = listOf(
        "claude-opus-5-5" to "Claude Opus 5.5 (mejor calidad)",
        "claude-sonnet-5-5" to "Claude Sonnet 5.5 (rápido)",
        "claude-haiku-5-5" to "Claude Haiku 5.5 (más barato)",
    )

    /** Límite de texto enviado (~150 000 tokens): acota el coste de cada consulta. */
    private const val MAX_CHARS = 600_000

    // La clave va en unas preferencias aparte para que no entre en copias ni sincronización
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("assistant_secret", Context.MODE_PRIVATE)
    private fun cache(c: Context) = c.applicationContext.getSharedPreferences("assistant_cache", Context.MODE_PRIVATE)

    fun apiKey(c: Context): String? = sp(c).getString("apiKey", null)?.takeIf { it.isNotBlank() }
    fun setApiKey(c: Context, key: String?) {
        sp(c).edit().putString("apiKey", key?.trim()).apply()
        client = null
    }

    fun model(c: Context): String = sp(c).getString("model", MODELS.first().first) ?: MODELS.first().first
    fun setModel(c: Context, m: String) = sp(c).edit().putString("model", m).apply()

    @Volatile private var client: AnthropicClient? = null
    @Volatile private var clientKey: String? = null

    private fun client(c: Context): AnthropicClient {
        val key = apiKey(c) ?: throw IllegalStateException("Falta la clave de API")
        client?.let { if (clientKey == key) return it }
        return AnthropicOkHttpClient.builder().apiKey(key).build().also {
            client = it
            clientKey = key
        }
    }

    /** Texto leído hasta ahora, con los títulos de capítulo; recortado por el principio si es muy largo. */
    fun textUpTo(book: EpubBook, paginator: Paginator, chapter: Int, offset: Int): Pair<String, Boolean> {
        val sb = StringBuilder()
        for (ch in 0..chapter.coerceAtMost(book.spine.size - 1)) {
            val t = paginator.textOf(ch)
            val end = if (ch == chapter) offset.coerceIn(0, t.length) else t.length
            if (end == 0) continue
            sb.append("\n\n## ").append(book.chapterTitle(ch)).append("\n\n")
            sb.append(t.subSequence(0, end).toString().replace('￼', ' '))
        }
        val truncated = sb.length > MAX_CHARS
        val text = if (truncated) sb.substring(sb.length - MAX_CHARS) else sb.toString()
        return text to truncated
    }

    private fun system(title: String, author: String, text: String, truncated: Boolean): List<BetaTextBlockParam> {
        val rules = """
            Eres un asistente de lectura dentro de una app de libros electrónicos. El lector va por la mitad
            de «$title»${if (author.isNotBlank()) " de $author" else ""}. Abajo tienes el texto que ya ha leído,
            hasta su página actual${if (truncated) " (por longitud, sólo la parte más reciente)" else ""}.
            Reglas:
            - Usa SOLO ese texto. Nunca adelantes ni insinúes lo que pasa después, aunque conozcas el libro.
            - Si algo no se puede responder con lo leído, dilo con naturalidad.
            - Responde en el idioma del lector (español salvo que te escriba en otro), claro y sin relleno.
        """.trimIndent()
        return listOf(
            BetaTextBlockParam.builder().text(rules).build(),
            // El texto del libro va en un bloque en caché: preguntar varias veces cuesta mucho menos
            BetaTextBlockParam.builder()
                .text("<libro>\n$text\n</libro>")
                .cacheControl(BetaCacheControlEphemeral.builder().build())
                .build(),
        )
    }

    /** Una pregunta con su historial; bloqueante (llamar fuera del hilo principal). */
    fun ask(
        c: Context,
        title: String,
        author: String,
        bookText: String,
        truncated: Boolean,
        history: List<Pair<String, String>>,
        question: String,
        maxTokens: Long = 4000L,
    ): String {
        val b = MessageCreateParams.builder()
            .model(model(c))
            .maxTokens(maxTokens)
            .systemOfBetaTextBlockParams(system(title, author, bookText, truncated))
        history.forEach { (q, a) ->
            b.addUserMessage(q)
            b.addAssistantMessage(a)
        }
        b.addUserMessage(question)
        // Si un filtro de seguridad rechaza la petición, el servidor la reintenta con otro modelo
        if (model(c) != "claude-haiku-5-5") {
            b.addBeta("server-side-fallback-2026-07-01")
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        val response = try {
            client(c).beta().messages().create(b.build())
        } catch (e: UnauthorizedException) {
            throw IllegalStateException("La clave de API no es válida. Revísala en Ajustes del asistente.")
        } catch (e: RateLimitException) {
            throw IllegalStateException("Demasiadas consultas seguidas. Espera un momento y vuelve a intentarlo.")
        } catch (e: AnthropicServiceException) {
            throw IllegalStateException("El servicio respondió con un error (${e.statusCode()}). Inténtalo más tarde.")
        }
        if (response.stopReason().map { it.toString() }.orElse("") == "refusal") {
            throw IllegalStateException("El asistente no pudo responder a esto.")
        }
        val out = response.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("")
        if (out.isBlank()) throw IllegalStateException("No hubo respuesta. Inténtalo de nuevo.")
        return out.trim()
    }

    fun summary(c: Context, title: String, author: String, text: String, truncated: Boolean): String = ask(
        c, title, author, text, truncated, emptyList(),
        "Hazme un resumen de lo que he leído hasta ahora para retomar la lectura: primero 3–5 frases con " +
            "lo esencial de la historia, luego una lista breve de lo que pasó en los últimos capítulos que leí " +
            "y en qué punto exacto me quedé. Sin spoilers de lo que viene.",
    )

    data class Character(val name: String, val role: String, val description: String)

    fun characters(c: Context, title: String, author: String, text: String, truncated: Boolean): List<Character> {
        val raw = ask(
            c, title, author, text, truncated, emptyList(),
            "Haz la lista de personajes que han aparecido hasta ahora en lo que he leído (máximo 30, de más a " +
                "menos importante). Responde SOLO con JSON válido, sin texto antes ni después, con esta forma: " +
                "[{\"nombre\": \"...\", \"papel\": \"protagonista|secundario|mencionado\", " +
                "\"descripcion\": \"quién es y qué ha hecho hasta ahora, en una o dos frases\"}]",
            maxTokens = 8000L,
        )
        val json = raw.substringAfter('[', "").substringBeforeLast(']', "")
        val arr = runCatching { JSONArray("[$json]") }.getOrElse { throw IllegalStateException("No se pudo leer la lista de personajes.") }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Character(o.optString("nombre"), o.optString("papel"), o.optString("descripcion")).takeIf { it.name.isNotBlank() }
        }
    }

    // ---------------------------------------------------------------- caché local de resultados

    fun saveCached(c: Context, bookId: String, kind: String, page: Int, value: String) {
        cache(c).edit().putString("$bookId|$kind", JSONObject().put("page", page).put("value", value).toString()).apply()
    }

    /** Resultado guardado y la página en la que se generó. */
    fun cached(c: Context, bookId: String, kind: String): Pair<Int, String>? = runCatching {
        val o = JSONObject(cache(c).getString("$bookId|$kind", null) ?: return null)
        o.getInt("page") to o.getString("value")
    }.getOrNull()

    fun charactersToJson(list: List<Character>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("nombre", it.name).put("papel", it.role).put("descripcion", it.description)) }
    }.toString()

    fun charactersFromJson(s: String): List<Character> = runCatching {
        val arr = JSONArray(s)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Character(o.optString("nombre"), o.optString("papel"), o.optString("descripcion"))
        }
    }.getOrDefault(emptyList())
}
