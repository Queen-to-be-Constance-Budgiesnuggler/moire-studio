package dk.cbavf.studio.vector3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class SvgWriterTest {
    private val solid = FaceDraw(floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f), 0xFF336699.toInt(), 5.0, floatArrayOf(1f, 2f, 3.5f, 4f), 0xFF000000.toInt(), 1.25f)
    private val glass = FaceDraw(floatArrayOf(0f, 0f, 5f, 0f, 5f, 5f, 0f, 5f), 0x80FF0000.toInt(), 3.0, FloatArray(0), 0xFF000000.toInt(), 1f)
    private val frame = Frame(200, 100, 0xFFFFFFFF.toInt(), listOf(solid, glass))
    private val svg = SvgWriter.write(frame)

    private fun count(s: String) = Regex(Regex.escape(s)).findAll(svg).count()

    @Test fun theFileIsWellFormedXml() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(svg.byteInputStream())
        assertEquals("svg", doc.documentElement.tagName)
    }

    @Test fun theRootHasTheFramesSize() {
        assertTrue(svg.contains("width=\"200\" height=\"100\" viewBox=\"0 0 200 100\""))
        assertTrue(svg.contains("xmlns=\"http://www.w3.org/2000/svg\""))
    }

    @Test fun theBackgroundComesFirst() {
        assertTrue(svg.indexOf("<rect") < svg.indexOf("<polygon"))
        assertTrue(svg.contains("<rect width=\"200\" height=\"100\" fill=\"#ffffff\"/>"))
    }

    @Test fun eachFaceIsOnePolygonInOrder() {
        assertEquals(2, count("<polygon"))
        assertTrue(svg.indexOf("#336699") < svg.indexOf("#ff0000"))
    }

    @Test fun polygonPointsArePairs() {
        assertTrue(svg.contains("points=\"0,0 10,0 10,10\""))
    }

    @Test fun onlyFacesWithLinesGetAPath() {
        assertEquals(1, count("<path"))
        assertTrue(svg.contains("d=\"M1 2L3.5 4\""))
        assertTrue(svg.contains("stroke=\"#000000\" stroke-width=\"1.25\""))
    }

    @Test fun seeThroughFillsCarryAnOpacity() {
        assertTrue(svg.contains("fill=\"#ff0000\" fill-opacity=\"0.5\""))
        assertFalse(svg.contains("fill=\"#336699\" fill-opacity"))
    }

    @Test fun numbersUseADotAndTwoDecimalsAtMost() {
        assertEquals("1.5", SvgWriter.num(1.5f))
        assertEquals("2", SvgWriter.num(2f))
        assertEquals("3.14", SvgWriter.num(3.14159f))
        assertEquals("100", SvgWriter.num(100f))
        assertEquals("0", SvgWriter.num(-0.001f))
        assertEquals("-7.25", SvgWriter.num(-7.25f))
    }

    @Test fun hexDropsTheAlpha() {
        assertEquals("#0a0b0c", SvgWriter.hex(0x800A0B0C.toInt()))
    }

    @Test fun aRenderedSceneWritesOnePolygonPerFace() {
        val f = Render3d.frame(Scene.DEFAULT, OrbitCamera(), 640, 480)
        val text = SvgWriter.write(f)
        assertEquals(f.faces.size, Regex("<polygon").findAll(text).count())
        assertTrue(text.trimEnd().endsWith("</svg>"))
    }
}
