package dk.cbavf.studio.studio

import org.junit.Assert.*
import org.junit.Test

class UserConfigTest {
    @Test fun profileRoundTrips() {
        val p = UserProfile(name = "A", handle = "@a", uiScale = 1.25f, haptics = false, debugLevel = 2,
            mathParams = mapOf("k" to 2.5), enabledPlugins = listOf("rgb-parade"))
        val back = UserConfigJson.decodeProfile(UserConfigJson.encode(p))
        assertEquals(p, back)
    }
    @Test fun workspaceRoundTrips() {
        val w = UserWorkspace(project = "demo", lastRoom = "Draw")
        assertEquals(w, UserConfigJson.decodeWorkspace(UserConfigJson.encode(w)))
    }
    @Test fun badInputGivesDefaults() {
        assertEquals(UserProfile(), UserConfigJson.decodeProfile(null))
        assertEquals(UserProfile(), UserConfigJson.decodeProfile("   "))
        assertEquals(UserProfile(), UserConfigJson.decodeProfile("{not json"))
        assertEquals(UserWorkspace(), UserConfigJson.decodeWorkspace("garbage"))
    }
    @Test fun unknownKeysAreIgnoredAndMissingKeysDefault() {
        val p = UserConfigJson.decodeProfile("{\"name\":\"Z\",\"future\":123}")
        assertEquals("Z", p.name)
        assertEquals(1f, p.uiScale, 0f)
        assertTrue(p.haptics)
    }
    @Test fun cleanClamps() {
        val c = UserConfigJson.clean(UserProfile(uiScale = 9f, debugLevel = 17))
        assertEquals(1.4f, c.uiScale, 0f)
        assertEquals(3, c.debugLevel)
        val d = UserConfigJson.clean(UserProfile(uiScale = 0.1f, debugLevel = -4))
        assertEquals(0.8f, d.uiScale, 0f)
        assertEquals(0, d.debugLevel)
        val decoded = UserConfigJson.decodeProfile("{\"uiScale\":5.0,\"debugLevel\":9}")
        assertEquals(1.4f, decoded.uiScale, 0f)
        assertEquals(3, decoded.debugLevel)
    }
    @Test fun migrationReadsTheOldKeys() {
        val prefs = mapOf<String, Any?>("theme" to "Monokai", "uiScale" to 1.2, "startRoom" to "Draw",
            "keepScreenOn" to true, "haptics" to false, "profileName" to "N", "profileHandle" to "@h",
            "profileAbout" to "about", "project" to "p1", "room" to "Nodes")
        val loop = mapOf<String, Any?>("role" to "host", "host" to "100.1.2.3")
        val (p, w) = UserConfigJson.migrate(prefs, loop)
        assertEquals("Monokai", p.theme); assertEquals(1.2f, p.uiScale, 1e-6f); assertEquals("Draw", p.startRoom)
        assertTrue(p.keepScreenOn); assertFalse(p.haptics)
        assertEquals("N", p.name); assertEquals("@h", p.handle); assertEquals("about", p.about)
        assertEquals("host", p.loopRole); assertEquals("100.1.2.3", p.loopHost)
        assertEquals("p1", w.project); assertEquals("Nodes", w.lastRoom)
    }
    @Test fun migrationIgnoresWrongTypesAndMissingKeys() {
        val (p, w) = UserConfigJson.migrate(mapOf("theme" to 5, "haptics" to "yes", "uiScale" to "big"), emptyMap())
        assertEquals(UserProfile(), p)
        assertEquals(UserWorkspace(), w)
        val (p2, _) = UserConfigJson.migrate(mapOf("uiScale" to 7), emptyMap())
        assertEquals(1.4f, p2.uiScale, 0f)
    }
}
