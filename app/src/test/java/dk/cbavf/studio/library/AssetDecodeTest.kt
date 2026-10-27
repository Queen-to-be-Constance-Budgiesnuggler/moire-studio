package dk.cbavf.studio.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetDecodeTest {
    @Test fun aSmallImageIsReadWhole() {
        assertEquals(1, AssetDecode.sampleSize(300, 360))
    }

    @Test fun subsamplingKeepsTheLongSideAtLeastTheAskedSize() {
        // 4032 / 8 = 504 >= 360, 4032 / 16 = 252 < 360
        assertEquals(8, AssetDecode.sampleSize(4032, 360))
    }

    @Test fun exactlyTwiceTheSizeHalves() {
        assertEquals(2, AssetDecode.sampleSize(720, 360))
    }

    @Test fun fullSizeIsNeverSubsampled() {
        assertEquals(1, AssetDecode.sampleSize(12000, Int.MAX_VALUE))
    }

    @Test fun anUnknownSizeIsReadWhole() {
        assertEquals(1, AssetDecode.sampleSize(0, 360))
        assertEquals(1, AssetDecode.sampleSize(-1, 360))
    }

    @Test fun aZeroTargetDoesNotLoopForever() {
        // read as if one pixel were asked for
        assertEquals(4096, AssetDecode.sampleSize(4096, 0))
    }

    @Test fun onlyGridSizesAreKept() {
        assertTrue(AssetDecode.cacheable(360))
        assertTrue(AssetDecode.cacheable(AssetDecode.CACHE_MAX_PX))
        assertFalse(AssetDecode.cacheable(2048))
        assertFalse(AssetDecode.cacheable(Int.MAX_VALUE))
        assertFalse(AssetDecode.cacheable(0))
    }

    @Test fun aCachedPictureIsUsedWithoutLoading() {
        var loads = 0
        val got = AssetDecode.cachedOrLoad("k", 360, { "cached" }, { loads++; "loaded" }, { _, _ -> })
        assertEquals("cached", got)
        assertEquals(0, loads)
    }

    @Test fun aMissingGridPictureIsLoadedAndKept() {
        val kept = HashMap<String, String>()
        val got = AssetDecode.cachedOrLoad("k", 360, kept::get, { "loaded" }, { k, v -> kept[k] = v })
        assertEquals("loaded", got)
        assertEquals("loaded", kept["k"])
    }

    @Test fun aFullSizePictureIsLoadedButNotKept() {
        val kept = HashMap<String, String>()
        val got = AssetDecode.cachedOrLoad("k", Int.MAX_VALUE, kept::get, { "loaded" }, { k, v -> kept[k] = v })
        assertEquals("loaded", got)
        assertTrue(kept.isEmpty())
    }

    @Test fun eachKeyGetsItsOwnPicture() {
        val kept = HashMap<String, String>()
        val a = AssetDecode.cachedOrLoad(AssetDecode.cacheKey("/p/a.png", 360), 360, kept::get, { "a" }, { k, v -> kept[k] = v })
        val b = AssetDecode.cachedOrLoad(AssetDecode.cacheKey("/p/b.png", 360), 360, kept::get, { "b" }, { k, v -> kept[k] = v })
        assertEquals("a", a)
        assertEquals("b", b)
    }

    @Test fun aFailedLoadIsNotKept() {
        val kept = HashMap<String, String>()
        assertEquals(null, AssetDecode.cachedOrLoad<String>("k", 360, kept::get, { null }, { k, v -> kept[k] = v }))
        assertTrue(kept.isEmpty())
    }

    @Test fun theSameFileAtTwoSizesHasTwoKeys() {
        assertNotEquals(AssetDecode.cacheKey("/p/a.png", 360), AssetDecode.cacheKey("/p/a.png", 720))
        assertEquals(AssetDecode.cacheKey("/p/a.png", 360), AssetDecode.cacheKey("/p/a.png", 360))
    }
}
