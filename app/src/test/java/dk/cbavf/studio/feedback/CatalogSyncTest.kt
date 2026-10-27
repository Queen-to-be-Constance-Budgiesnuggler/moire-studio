package dk.cbavf.studio.feedback

import dk.cbavf.studio.ui.Room
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The collector and the catalog name rooms by text, so they drift when a room is added or renamed. These tests read the files
 * from the repo (Gradle runs unit tests in the app folder) and check them against [Room].
 */
class CatalogSyncTest {
    private fun repoFile(path: String): File =
        listOf(File("../$path"), File(path)).firstOrNull { it.exists() } ?: error("not found: $path")

    private val titles = Room.entries.map { it.title }

    @Test fun theCollectorKnowsEveryRoomTitle() {
        val text = repoFile("tools/feedback-server/server.py").readText()
        val block = Regex("""ROOM_TITLES = \(([^)]*)\)""").find(text)?.groupValues?.get(1) ?: error("ROOM_TITLES not found")
        val listed = Regex("\"([^\"]+)\"").findAll(block).map { it.groupValues[1] }.toList()
        assertEquals(titles, listed)
    }

    @Test fun everyCatalogTriggerNamesARealRoom() {
        val tests = Json.parseToJsonElement(repoFile("app/src/main/assets/usecases.json").readText()).jsonObject["tests"]!!.jsonObject
        val scopes = Room.entries.map { it.name }.toSet()
        for ((id, spec) in tests) {
            for (t in spec.jsonObject["all"]!!.jsonArray) {
                val trigger = t.jsonPrimitive.content
                val (kind, key) = trigger.split(":", limit = 2)
                val ok = when (kind) {
                    "tap" -> key.substringBefore("|") in titles && key.substringAfter("|", "").isNotBlank()
                    "room" -> key in titles
                    // a slider's key is the room's enum name, a dot, and the slider's label (see LabeledSlider)
                    "ctl" -> key.substringBefore(".") in scopes && key.substringAfter(".", "").isNotBlank()
                    else -> false
                }
                assertTrue("$id: $trigger", ok)
            }
        }
    }
}
