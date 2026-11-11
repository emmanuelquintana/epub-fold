package com.emmanuel.epubfold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitlesTest {
    @Test fun quitaExtensiones() {
        assertEquals("Civil War II", cleanBookTitle("Civil War II.cbz"))
        assertEquals("Civil War", cleanBookTitle("Civil War.cbz.zip"))
        assertEquals("Mi libro", cleanBookTitle("Mi_libro.epub"))
        assertEquals("Dune", cleanBookTitle("Dune"))
    }

    @Test fun ordenNaturalDePaginas() {
        val names = listOf("p10.jpg", "p2.jpg", "p1.jpg", "p02b.jpg")
        assertEquals(listOf("p1.jpg", "p2.jpg", "p02b.jpg", "p10.jpg"), names.sortedWith { a, b -> ComicBook.naturalCompare(a, b) })
    }

    @Test fun claveDeLibroEstable() {
        assertEquals(bookKey("Dune", "Frank Herbert"), bookKey(" dune ", "FRANK HERBERT"))
        assertTrue(bookKey("Dune", "Frank Herbert") != bookKey("Dune Messiah", "Frank Herbert"))
    }
}

class BionicTest {
    @Test fun negritaAlInicioDeCadaPalabra() {
        val text = "El lector abre un libro"
        val words = TextTools.bionicRanges(text).map { text.substring(it.first, it.last + 1) }
        assertEquals(listOf("E", "lec", "ab", "u", "li"), words)
    }

    @Test fun ignoraNumerosYSignos() {
        assertTrue(TextTools.bionicRanges("12, 34! ¿?").isEmpty())
    }
}

class FootnoteTest {
    @Test fun parrafoDeLaNota() {
        val html = """<p>Texto<a href="#n1">1</a></p><aside id="n1" epub:type="footnote"><p>Una <i>nota</i> breve.</p></aside>"""
        assertEquals("Una nota breve.", TextTools.elementText(html, "n1"))
    }

    @Test fun anclaDentroDelParrafo() {
        val html = """<p class="nota"><a id="nt3" href="cap1.xhtml#r3">3</a> Explicación de la nota.</p><p>Otra cosa</p>"""
        assertEquals("3 Explicación de la nota.", TextTools.elementText(html, "nt3"))
    }

    @Test fun idInexistente() {
        assertNull(TextTools.elementText("<p id=\"a\">x</p>", "b"))
    }
}

class ImageAnalysisTest {
    /** Página blanca con viñetas negras en las posiciones dadas (x0, y0, x1, y1). */
    private fun page(w: Int, h: Int, vararg boxes: IntArray): IntArray {
        val lum = IntArray(w * h) { 255 }
        boxes.forEach { (x0, y0, x1, y1) -> for (y in y0 until y1) for (x in x0 until x1) lum[y * w + x] = 40 }
        return lum
    }

    @Test fun recortaMargenes() {
        val lum = page(100, 150, intArrayOf(10, 20, 90, 130))
        val b = ImageAnalysis.contentBounds(lum, 100, 150)
        assertNotNull(b)
        assertEquals(listOf(10, 20, 90, 130), b!!.toList())
    }

    @Test fun detectaViñetasEnOrden() {
        // Dos filas: arriba una viñeta ancha; abajo dos viñetas lado a lado
        val lum = page(200, 300,
            intArrayOf(10, 10, 190, 140),
            intArrayOf(10, 160, 95, 290),
            intArrayOf(105, 160, 190, 290))
        val p = ImageAnalysis.panels(lum, 200, 300)
        assertEquals(3, p.size)
        assertTrue(p[0][1] < p[1][1])          // la de arriba primero
        assertTrue(p[1][0] < p[2][0])          // abajo, izquierda antes que derecha
        val manga = ImageAnalysis.panels(lum, 200, 300, rtl = true)
        assertTrue(manga[1][0] > manga[2][0])  // en manga, derecha antes que izquierda
    }

    @Test fun paginaSinViñetasEsUnaSola() {
        val lum = page(100, 100, intArrayOf(0, 0, 100, 100))
        assertEquals(1, ImageAnalysis.panels(lum, 100, 100).size)
    }
}
