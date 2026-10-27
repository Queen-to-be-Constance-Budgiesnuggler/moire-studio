---
id: T-007
executor: local            # local | claude | user
wave: 1
depends: []
files_out: [app/src/main/java/dk/cbavf/studio/studio/UserConfig.kt]
test_src: tasks/tests/T-007/UserConfigTest.kt
test_dest: app/src/test/java/dk/cbavf/studio/studio/UserConfigTest.kt
test_filter: dk.cbavf.studio.studio.UserConfigTest
max_retries: 2
status: done                # model attempts failed on a JSON import; written by hand against the same tests
---
## Spec
The data model and JSON helpers for the two user files. This one file may use `kotlinx.serialization` (`@Serializable`, `kotlinx.serialization.json.Json`); everything else stays pure Kotlin.

```kotlin
@Serializable data class UserProfile(
    val version: Int = 1, val name: String = "", val handle: String = "", val about: String = "",
    val theme: String = "", val uiScale: Float = 1f, val startRoom: String = "",
    val keepScreenOn: Boolean = false, val haptics: Boolean = true,
    val loopRole: String = "", val loopHost: String = "",
    val debugLevel: Int = 0, val terminalVisible: Boolean = false, val copyAsScript: Boolean = false,
    val abTwoFingerHold: Boolean = false, val paletteNotification: Boolean = false,
    val mathParams: Map<String, Double> = emptyMap(), val enabledPlugins: List<String> = emptyList())

@Serializable data class UserWorkspace(
    val version: Int = 1, val project: String = "default", val lastRoom: String = "")

object UserConfigJson {
    fun encode(p: UserProfile): String
    fun encode(w: UserWorkspace): String
    fun decodeProfile(text: String?): UserProfile
    fun decodeWorkspace(text: String?): UserWorkspace
    fun clean(p: UserProfile): UserProfile
    fun migrate(prefs: Map<String, Any?>, loop: Map<String, Any?>): Pair<UserProfile, UserWorkspace>
}
```

- Use one private `Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }`.
- `decodeProfile` / `decodeWorkspace`: `null`, blank or malformed text returns the default object (never throws). The profile result goes through `clean`.
- `clean`: clamps `uiScale` to `0.8f..1.4f` and `debugLevel` to `0..3`.
- `migrate` reads the old SharedPreferences values from plain maps. From `prefs`: `theme` (String), `uiScale` (any `Number`, to Float), `startRoom` (String), `keepScreenOn` and `haptics` (Boolean), `profileName`, `profileHandle`, `profileAbout` (String) go into the profile as `theme`, `uiScale`, `startRoom`, `keepScreenOn`, `haptics`, `name`, `handle`, `about`. `project` and `room` (String) go into the workspace as `project` and `lastRoom`. From `loop`: `role` and `host` (String) become `loopRole` and `loopHost`. Missing keys or values of the wrong type keep the defaults. The profile result goes through `clean`.

Rules for the file: pure Kotlin with no Android imports, start with the package line `package dk.cbavf.studio.studio`, import exactly what you use (every `kotlin.math` function you call must be imported), write function bodies as plain statements with local `val`s and early `return`s, count your parentheses, and put a one-line KDoc on each public function (no @param or @return tags, keep it short).

## Acceptance
`UserConfigTest` passes.
