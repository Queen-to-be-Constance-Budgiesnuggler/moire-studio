package dk.cbavf.studio.compose

import dk.cbavf.studio.compose.model.ComposeDoc
import dk.cbavf.studio.compose.model.LayerSource
import dk.cbavf.studio.compose.model.TextAlignment
import dk.cbavf.studio.compose.model.TextFont
import dk.cbavf.studio.compose.model.TextLayerSource
import dk.cbavf.studio.library.ProjectStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TextLayerTest {
    private fun text(align: TextAlignment = TextAlignment.Left) =
        TextLayerSource("Hi", 40f, 0xFF000000, widthPx = 102, heightPx = 50, align = align)

    @Test
    fun leftAlignedLinesStartAtTheLeftEdge() {
        assertEquals(0f, text().lineX(60f), 0f)
    }

    @Test
    fun centredLinesSitInTheMiddle() {
        assertEquals(20f, text(TextAlignment.Centre).lineX(60f), 0f)
    }

    @Test
    fun rightAlignedLinesEndAtTheRightEdge() {
        assertEquals(40f, text(TextAlignment.Right).lineX(60f), 0f)
    }

    @Test
    fun theWidestLineIsNeverPushedOffTheLayer() {
        assertEquals(0f, text(TextAlignment.Right).lineX(100f), 0f)
        assertEquals(0f, text(TextAlignment.Centre).lineX(100f), 0f)
    }

    @Test
    fun boldAndItalicMatchTheTypefaceStyleBits() {
        assertEquals(0, text().styleBits)
        assertEquals(1, text().copy(bold = true).styleBits)
        assertEquals(2, text().copy(italic = true).styleBits)
        assertEquals(3, text().copy(bold = true, italic = true).styleBits)
    }

    @Test
    fun olderTextLayersOpenInTheDefaultFont() {
        // Saved before fonts existed: no font, italic, alignment or spacing in the file.
        val json = """{"type":"text","text":"Old","sizePx":30.0,"argb":4278190080,"bold":true,"widthPx":80,"heightPx":40}"""
        val src = ProjectStore.json.decodeFromString(LayerSource.serializer(), json) as TextLayerSource
        assertEquals(TextFont.Sans, src.font)
        assertFalse(src.italic)
        assertEquals(TextAlignment.Left, src.align)
        assertEquals(0f, src.letterSpacing, 0f)
    }

    @Test
    fun fontAndStyleSurviveSaving() {
        val src = text(TextAlignment.Centre).copy(font = TextFont.Serif, italic = true, letterSpacing = 0.1f)
        val back = ProjectStore.json.decodeFromString(LayerSource.serializer(), ProjectStore.json.encodeToString(LayerSource.serializer(), src))
        assertEquals(src, back)
    }

    @Test
    fun everyFontHasItsOwnLabelAndFamily() {
        assertEquals(TextFont.entries.size, TextFont.entries.map { it.label }.distinct().size)
        assertEquals(TextFont.entries.size, TextFont.entries.map { it.family }.distinct().size)
    }

    @Test
    fun newTextTakesTheCurrentColour() {
        val s = ComposeState().apply { edit.load(ComposeDoc(400, 300)) }
        s.color = 0xFFFF0000
        s.addText(text().copy(font = TextFont.Mono))
        val src = s.selected!!.source as TextLayerSource
        assertEquals(0xFFFF0000, src.argb)
        assertEquals(TextFont.Mono, src.font)
    }
}
