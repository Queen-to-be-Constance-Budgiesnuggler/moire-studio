package dk.cbavf.studio.fft

import dk.cbavf.studio.fft.model.FftDoc
import dk.cbavf.studio.fft.model.FilterShape
import dk.cbavf.studio.fft.model.SpectrumBrush
import dk.cbavf.studio.fft.model.SpectrumDab
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.library.Recipe
import org.junit.Assert.assertEquals
import org.junit.Test

/** What an FFT save keeps, so that reopening it brings back the whole edit, the phase swap included. */
class FftRecipeTest {
    private val edit = FftDoc(
        sourceAssetId = "a1", size = 1024, filter = FilterShape.BandPass, radius = 0.2f, bandWidth = 0.07f,
        dabs = listOf(SpectrumDab(0.1f, -0.2f, 0.03f, SpectrumBrush.Keep)), boost = 5f, phaseFromAssetId = "b2",
    )

    @Test fun aSavedRecipeKeepsThePhaseSwapAndEverythingElse() {
        val text = ProjectStore.json.encodeToString(Recipe.serializer(), Recipe(fft = edit))
        val back = ProjectStore.json.decodeFromString(Recipe.serializer(), text).fft
        assertEquals(edit, back)
        assertEquals("b2", back?.phaseFromAssetId)
        assertEquals("FFT 1024 · bandpass · 1 dabs · phase swap", back?.summary)
    }
}
