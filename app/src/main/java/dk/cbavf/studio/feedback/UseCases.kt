package dk.cbavf.studio.feedback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The studio's use-case catalog (`assets/usecases.json`): for each checklist test, the things a person does when they carry it out
 * (taps on named controls, rooms visited, sliders used). The app only needs the tap list: it records a tap only when "Room|Label" is in
 * it, so a project name, layer name or tag the person typed can never leave the phone. Which tests were done is worked out on the
 * server side from the counts.
 */
class UseCaseCatalog(val taps: Set<String>) {
    /** The "Room|Label" key for a tap on [label] in [room], or null when the catalog does not name that control. */
    fun keyFor(room: String, label: String): String? = tapKey(room, label).takeIf { it in taps }

    companion object {
        val EMPTY = UseCaseCatalog(emptySet())

        fun tapKey(room: String, label: String): String = room.trim() + "|" + normalise(label)

        /** One line, single spaces, at most 60 characters: what a control's text is compared as. */
        fun normalise(label: String): String = label.trim().replace(Regex("\\s+"), " ").take(60)

        /** The tap keys named by any test in [json]; damaged or missing text gives an empty catalog. */
        fun parse(json: String?): UseCaseCatalog {
            if (json.isNullOrBlank()) return EMPTY
            return runCatching {
                val tests = Json.parseToJsonElement(json).jsonObject["tests"]?.jsonObject ?: return EMPTY
                val taps = HashSet<String>()
                for ((_, spec) in tests) {
                    val all = (spec as? JsonObject)?.get("all") as? JsonArray ?: continue
                    for (t in all) {
                        val trigger = t.jsonPrimitive.content
                        if (trigger.startsWith("tap:")) taps += trigger.removePrefix("tap:")
                    }
                }
                UseCaseCatalog(taps)
            }.getOrDefault(EMPTY)
        }
    }
}
