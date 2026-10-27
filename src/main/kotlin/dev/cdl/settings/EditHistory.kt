// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settings

data class SettingChange(
    val kind: Kind,
    val changedKeys: Set<String>,
    val preview: PreviewKind,
) {

    enum class Kind {

        PUBLISH,

        APPLY,

        UNDO,

        DISCARD,

        PRESET,

        EXTERNAL,
    }

    val isEmpty: Boolean get() = changedKeys.isEmpty()

    val descriptors: List<SettingDescriptor>
        get() = changedKeys.mapNotNull { SettingRegistry.descriptor(it) }

    val groups: Set<String>
        get() = descriptors.mapTo(LinkedHashSet()) { it.group }

    fun touchesAny(namespaces: Set<String>): Boolean =
        changedKeys.any { key -> namespaces.any { key.startsWith(it) } }

    val shouldRecordInHistory: Boolean
        get() = kind == Kind.APPLY && !isEmpty

    companion object {

        fun empty(before: SettingsSnapshot): SettingChange =
            SettingChange(Kind.PUBLISH, emptySet(), PreviewKind.IMMEDIATE)
    }
}

class EditHistory(private var limit: Int = DEFAULT_LIMIT) {

    private data class Entry(val state: SettingsSnapshot, val record: SettingChange?)

    private val entries = ArrayList<Entry>(limit + 1)

    private var cursor = 0

    private var lastCommitAtMs = 0L

    val canUndo: Boolean get() = cursor > 0

    val canRedo: Boolean get() = cursor < entries.lastIndex

    val state: SettingsSnapshot
        get() = entries.getOrNull(cursor)?.state ?: SettingsSnapshot.defaults()

    val undoRecord: SettingChange?
        get() = if (canUndo) entries[cursor].record else null

    val redoRecord: SettingChange?
        get() = if (canRedo) entries[cursor + 1].record else null

    val size: Int get() = entries.size - 1

    fun reset(baseline: SettingsSnapshot) {
        entries.clear()
        entries += Entry(baseline, null)
        cursor = 0
        lastCommitAtMs = 0L
    }

    fun commit(
        state: SettingsSnapshot,
        change: SettingChange,
        mergeWindowMs: Long = DEFAULT_MERGE_WINDOW_MS,
    ): Boolean {
        if (change.isEmpty) return false

        val head = entries.getOrNull(cursor)?.record
        val now = System.currentTimeMillis()

        val mergeable = head != null &&
            head.changedKeys == change.changedKeys &&
            cursor == entries.lastIndex &&
            now - lastCommitAtMs <= mergeWindowMs

        if (mergeable) {

            entries[cursor] = Entry(state, head)
            lastCommitAtMs = now
            return true
        }

        if (cursor < entries.lastIndex) {
            entries.subList(cursor + 1, entries.size).clear()
        }

        entries += Entry(state, change)
        cursor = entries.lastIndex
        lastCommitAtMs = now

        while (entries.size > limit + 1) {
            entries.removeAt(0)
            cursor--
        }
        return false
    }

    fun undo(): SettingsSnapshot? = if (canUndo) entries[--cursor].state else null

    fun redo(): SettingsSnapshot? = if (canRedo) entries[++cursor].state else null

    fun resize(newLimit: Int) {
        require(newLimit >= MIN_LIMIT) { "栈深不得小于 $MIN_LIMIT：$newLimit" }

        require(newLimit <= MAX_LIMIT) { "栈深不得大于 $MAX_LIMIT：$newLimit" }

        if (newLimit == limit) return
        limit = newLimit
        while (entries.size > limit + 1) {
            entries.removeAt(0)
            cursor--
        }
    }

    companion object {

        const val DEFAULT_LIMIT = 200

        const val MIN_LIMIT = 50

        const val MAX_LIMIT = 500

        const val DEFAULT_MERGE_WINDOW_MS = 400L
    }
}

class MenuSessionHistory {

    private var history: EditHistory? = null

    val isActive: Boolean get() = history != null

    val current: EditHistory? get() = history

    fun beginSession(baseline: SettingsSnapshot, limit: Int = EditHistory.DEFAULT_LIMIT): EditHistory {
        val fresh = EditHistory(limit)
        fresh.reset(baseline)
        history = fresh
        return fresh
    }

    fun endSession() {
        history = null
    }

    val hasCommittedEdits: Boolean
        get() = (history?.size ?: 0) > 0

    val mayHaveUncommittedEdits: Boolean get() = isActive
}
