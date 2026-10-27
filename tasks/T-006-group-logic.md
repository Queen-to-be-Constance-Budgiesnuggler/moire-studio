---
id: T-006
executor: local            # local | claude | user
wave: 1
depends: []
files_out: [app/src/main/java/dk/cbavf/studio/draw/groups/GroupLogic.kt]
test_src: tasks/tests/T-006/GroupLogicTest.kt
test_dest: app/src/test/java/dk/cbavf/studio/draw/groups/GroupLogicTest.kt
test_filter: dk.cbavf.studio.draw.groups.GroupLogicTest
max_retries: 2
status: done                # model attempt failed 3 tests (unknown group, kept parent/group, cycle); fixed by hand, see GroupLogic.kt
---
## Spec
Pure logic for nested layer groups in the Draw room. Declare these data classes and `object GroupLogic`:

```kotlin
data class GNode(val id: Int, val parent: Int?, val visible: Boolean = true)   // a group
data class LNode(val id: Int, val group: Int?, val visible: Boolean = true)    // a layer
data class DuplicateResult(val newLayers: List<LNode>, val newGroups: List<GNode>,
                           val layerIdMap: Map<Int, Int>, val groupIdMap: Map<Int, Int>)
object GroupLogic {
    fun effectiveVisible(layer: LNode, groups: List<GNode>): Boolean
    fun descendants(groupId: Int, groups: List<GNode>): Set<Int>
    fun layersIn(groupId: Int, layers: List<LNode>, groups: List<GNode>): List<LNode>
    fun duplicate(layers: List<LNode>, groups: List<GNode>, selected: Set<Int>,
                  nextLayerId: Int, nextGroupId: Int): DuplicateResult
}
```

- `effectiveVisible`: the layer's own `visible` AND the `visible` of its group AND of every ancestor group up the `parent` chain. Unknown group ids count as visible. Guard against a parent cycle: stop after `groups.size + 1` steps and treat a cycle as not visible.
- `descendants`: ids of all groups nested below `groupId` (children, grandchildren and so on), not including `groupId` itself in a normal tree. Must terminate on cycles (track visited ids).
- `layersIn`: layers whose group is `groupId` or any descendant of it, in the original list order.
- `duplicate`: copies the layers whose ids are in `selected`. A group is **fully selected** if it contains at least one layer (counting descendants) and every layer in it and its descendants is in `selected`. Fully selected groups are copied too, with new ids. New layer ids start at `nextLayerId` and new group ids at `nextGroupId`, assigned in ascending order of the original ids. A copied layer's group becomes the copy of its group if that group was copied, otherwise it keeps its original group id (or null). A copied group's parent becomes the copy of its parent if the parent was copied, otherwise it keeps its original parent. Copies keep their `visible` flags. `newLayers` is in the original list order. `newGroups` is in ascending order of the original group ids. The result maps give original id to new id.

Rules for the file: pure Kotlin with no Android imports, start with the package line `package dk.cbavf.studio.draw.groups`, import exactly what you use (every `kotlin.math` function you call must be imported), write function bodies as plain statements with local `val`s and early `return`s, count your parentheses, and put a one-line KDoc on each public function (no @param or @return tags, keep it short).

## Acceptance
`GroupLogicTest` passes.
