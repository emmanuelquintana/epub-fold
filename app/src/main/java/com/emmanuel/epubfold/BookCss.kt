package com.emmanuel.epubfold

/**
 * Parte de la hoja de estilos del libro que se puede respetar sin romper los temas:
 * alineación, cursiva, negrita, tamaños relativos y elementos ocultos. Se aplica
 * reescribiendo el HTML antes de convertirlo en texto con formato.
 */
object BookCss {

    data class Rule(val tag: String?, val cls: String?, val props: Map<String, String>)

    private val VOID = setOf("br", "img", "hr", "meta", "link", "input", "image", "source", "col", "area", "wbr")

    /** Reglas de las hojas enlazadas en [rawHtml] y de sus bloques <style>. */
    fun rulesFor(rawHtml: String, dir: String, read: (String) -> String?, cache: HashMap<String, List<Rule>>): List<Rule> {
        val out = ArrayList<Rule>()
        Regex("<link[^>]+href\\s*=\\s*[\"']([^\"']+\\.css)[\"'][^>]*>", RegexOption.IGNORE_CASE).findAll(rawHtml).forEach { m ->
            val path = EpubBook.resolve(dir, m.groupValues[1])
            out += cache.getOrPut(path) { read(path)?.let { parse(it) } ?: emptyList() }
        }
        Regex("<style[^>]*>(.*?)</style>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(rawHtml)
            .forEach { out += parse(it.groupValues[1]) }
        return out
    }

    /** Reglas con selectores simples: «p», «.clase», «p.clase» (las demás se ignoran). */
    fun parse(css: String): List<Rule> {
        val clean = css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("@media[^{]*\\{([^{}]*\\{[^{}]*\\})*[^{}]*\\}"), "")
        val out = ArrayList<Rule>()
        Regex("([^{}@]+)\\{([^{}]*)\\}").findAll(clean).forEach { m ->
            val props = HashMap<String, String>()
            m.groupValues[2].split(';').forEach { d ->
                val k = d.substringBefore(':', "").trim().lowercase()
                val v = d.substringAfter(':', "").trim().lowercase().removeSuffix("!important").trim()
                if (k.isNotEmpty() && v.isNotEmpty()) props[k] = v
            }
            if (props.isEmpty()) return@forEach
            m.groupValues[1].split(',').map { it.trim() }.forEach { sel ->
                val s = Regex("^([a-zA-Z][a-zA-Z0-9]*)?(?:\\.([A-Za-z0-9_-]+))?$").find(sel) ?: return@forEach
                val tag = s.groupValues[1].lowercase().ifEmpty { null }
                val cls = s.groupValues[2].ifEmpty { null }
                if (tag != null || cls != null) out += Rule(tag, cls, props)
            }
        }
        return out
    }

    private fun sizeFactor(v: String): Float? = when {
        v.endsWith("em") -> v.removeSuffix("rem").removeSuffix("em").toFloatOrNull()
        v.endsWith("%") -> v.removeSuffix("%").toFloatOrNull()?.div(100f)
        v == "larger" || v == "large" -> 1.2f
        v == "x-large" -> 1.5f
        v == "xx-large" -> 2f
        v == "smaller" || v == "small" -> 0.85f
        v == "x-small" || v == "xx-small" -> 0.75f
        else -> null
    }

    /** Aplica las reglas a cada etiqueta de apertura y cierra lo añadido en su cierre. */
    fun apply(html: String, rules: List<Rule>): String {
        val byTag = rules.filter { it.cls == null }.groupBy { it.tag }
        val byClass = rules.filter { it.cls != null }.groupBy { it.cls }
        val sb = StringBuilder(html.length + 256)
        val stack = ArrayList<Pair<String, String>>() // etiqueta -> cierres añadidos
        val tagRe = Regex("<(/?)([a-zA-Z][a-zA-Z0-9:]*)([^>]*?)(/?)>")
        var last = 0
        for (m in tagRe.findAll(html)) {
            sb.append(html, last, m.range.first)
            last = m.range.last + 1
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase().substringAfter(':')
            val attrs = m.groupValues[3]
            val selfClosing = m.groupValues[4] == "/" || name in VOID
            if (closing) {
                // Cierra lo añadido para esta etiqueta (y lo que quedara abierto dentro)
                val idx = stack.indexOfLast { it.first == name }
                if (idx >= 0) {
                    for (i in stack.size - 1 downTo idx) sb.append(stack[i].second)
                    while (stack.size > idx) stack.removeAt(stack.size - 1)
                }
                sb.append(m.value)
                continue
            }
            val props = HashMap<String, String>()
            byTag[name]?.forEach { props.putAll(it.props) }
            Regex("class\\s*=\\s*[\"']([^\"']*)[\"']").find(attrs)?.groupValues?.get(1)?.split(Regex("\\s+"))?.forEach { c ->
                byClass[c]?.forEach { r -> if (r.tag == null || r.tag == name) props.putAll(r.props) }
            }
            if (props.isEmpty() || selfClosing) {
                sb.append(m.value)
                continue
            }
            var open = m.value
            val opens = StringBuilder()
            val closes = StringBuilder()
            props["text-align"]?.takeIf { it in setOf("center", "right", "left", "justify") }?.let { a ->
                if (!Regex("style\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(attrs)) {
                    val al = if (a == "justify") "left" else a
                    open = open.substring(0, open.length - (if (m.groupValues[4] == "/") 2 else 1)) + " style=\"text-align:$al\">"
                }
            }
            if (props["font-weight"]?.let { it == "bold" || it == "bolder" || (it.toIntOrNull() ?: 0) >= 600 } == true) {
                opens.append("<b>"); closes.insert(0, "</b>")
            }
            if (props["font-style"] == "italic" || props["font-style"] == "oblique") {
                opens.append("<i>"); closes.insert(0, "</i>")
            }
            if (name !in setOf("h1", "h2", "h3", "h4", "h5", "h6")) {
                val f = props["font-size"]?.let { sizeFactor(it) }
                if (f != null && f >= 1.15f) { opens.append("<big>"); closes.insert(0, "</big>") }
                else if (f != null && f <= 0.9f) { opens.append("<small>"); closes.insert(0, "</small>") }
            }
            sb.append(open).append(opens)
            stack += name to closes.toString()
        }
        sb.append(html, last, html.length)
        return sb.toString()
    }
}
