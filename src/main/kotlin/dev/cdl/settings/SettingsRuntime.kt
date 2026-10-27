// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package dev.cdl.settings

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper

class SettingsSnapshot internal constructor(
    private val values: Map<String, Any>,
) {

    fun raw(key: String): Any? = values[key]

    fun with(key: String, value: Any): SettingsSnapshot =
        SettingsSnapshot(values + (key to value))

    fun withAll(overrides: Map<String, Any>): SettingsSnapshot =
        if (overrides.isEmpty()) this else SettingsSnapshot(values + overrides)

    fun asMap(): Map<String, Any> = values

    fun isMostlyDefaults(): Boolean {
        val total = SettingRegistry.all.size
        if (total == 0) return false
        val sameAsDefault = SettingRegistry.all.count { descriptor ->
            val current = values[descriptor.key] ?: return@count false

            runCatching { descriptor.coerce(current) == descriptor.default }.getOrDefault(false)
        }
        return sameAsDefault * 100 >= total * 80
    }

    private fun rawFor(descriptor: SettingDescriptor): Any? =
        if (descriptor.retired) null else values[descriptor.key]

    fun boolean(descriptor: SettingDescriptor): Boolean =
        descriptor.coerceBoolean(rawFor(descriptor))

    fun int(descriptor: SettingDescriptor): Int = descriptor.coerceInt(rawFor(descriptor))

    fun text(descriptor: SettingDescriptor): String = descriptor.coerceText(rawFor(descriptor))

    fun boolean(key: String): Boolean = SettingRegistry.descriptor(key).let { d ->
        if (d == null) false else boolean(d)
    }

    fun changedKeysAgainst(other: SettingsSnapshot): Set<String> {
        val keys = values.keys + other.values.keys
        return keys.filterTo(LinkedHashSet()) { values[it] != other.values[it] }
    }

    companion object {

        fun defaults(): SettingsSnapshot =
            SettingsSnapshot(SettingRegistry.all.associate { it.key to it.default })
    }
}

private const val COLOR_INT_SENTINEL: Int = Int.MIN_VALUE

private const val MAX_REPORTED_FALLBACK_KEYS: Int = 10

class SettingsStore(private val config: ConfigHelper) {

    companion object {

        @Volatile
        private var lastFallbackKeys: Set<String> = emptySet()
    }

    @Volatile
    var lastKnown: SettingsSnapshot = SettingsSnapshot.defaults()
        private set

    fun load(): SettingsSnapshot {
        val values = LinkedHashMap<String, Any>(SettingRegistry.all.size)

        val fallbackKeys = LinkedHashSet<String>()
        for (descriptor in SettingRegistry.all) {
            val raw = readRaw(descriptor)
            if (raw == null) {

                values[descriptor.key] = descriptor.default
                continue
            }
            val coerced = runCatching { descriptor.coerce(raw) }.getOrElse { descriptor.default }

            if (descriptor.differsFromRaw(coerced, raw)) {

                fallbackKeys.add(descriptor.key)
            }
            values[descriptor.key] = coerced
        }
        reportFallbacks(fallbackKeys)
        val snapshot = SettingsSnapshot(values)
        lastKnown = snapshot
        return snapshot
    }

    private fun reportFallbacks(keys: Set<String>) {
        if (keys == lastFallbackKeys) return
        lastFallbackKeys = keys
        if (keys.isEmpty()) return
        val shown = keys.take(MAX_REPORTED_FALLBACK_KEYS)
        val suffix = if (keys.size > shown.size) " …等 ${keys.size} 项" else ""
    }

    fun save(snapshot: SettingsSnapshot): Boolean {

        runCatching { config.reload() }
            .onFailure {  }

        var failed = 0
        for (descriptor in SettingRegistry.all) {

            val value = descriptor.storageValue(snapshot.raw(descriptor.key) ?: descriptor.default)
            runCatching { config.set(descriptor.key, value) }
                .onFailure { failed++ }
        }
        if (failed > 0) {
        }

        val saved = runCatching { config.save() }.getOrElse { false }
        if (saved) lastKnown = snapshot

        return saved && failed == 0
    }

    fun reloadIfChanged(): SettingsSnapshot? {

        val previous = lastKnown

        val ok = runCatching { config.reload() }.getOrElse { false }
        if (!ok) return null
        val fresh = load()
        return if (fresh.changedKeysAgainst(previous).isEmpty()) null else fresh
    }

    fun reloadAndLoad(): SettingsSnapshot? {
        val ok = runCatching { config.reload() }.getOrElse { false }
        if (!ok) return null
        return load()
    }

    fun configPath(): java.nio.file.Path? =
        runCatching { config.getConfigPath() }.getOrNull()

    fun dataDirectory(): java.nio.file.Path? = configPath()?.parent

    private fun readRaw(descriptor: SettingDescriptor): Any? = runCatching {
        when (descriptor.kind) {
            SettingKind.SWITCH ->
                config.get(descriptor.key, descriptor.defaultValueBoolean())

            SettingKind.SEEKBAR ->
                config.get(descriptor.key, descriptor.defaultValueInt().toFloat())

            SettingKind.COLOR -> {
                val text = config.get(descriptor.key, "")
                if (text.isEmpty()) {
                    descriptor.defaultValueInt()
                } else {
                    val native = config.get(descriptor.key, COLOR_INT_SENTINEL)
                    if (native != COLOR_INT_SENTINEL) {
                        native
                    } else {
                        parseHexOrNull(text) ?: descriptor.defaultValueInt()
                    }
                }
            }

            SettingKind.LIST, SettingKind.EDITTEXT, SettingKind.FONT ->
                config.get(descriptor.key, descriptor.defaultValueText())
        }
    }.getOrNull()
}

class SettingsRuntime internal constructor(
    private val store: SettingsStore,
    private val configManager: com.xuncorp.spw.workshop.api.config.ConfigManager,
) : AutoCloseable {

    @Volatile
    var live: SettingsSnapshot = SettingsSnapshot.defaults()
        private set

    @Volatile
    var saved: SettingsSnapshot = SettingsSnapshot.defaults()
        private set

    @Volatile
    var version: Long = 0L
        private set

    private val subscribers = java.util.concurrent.CopyOnWriteArrayList<(SettingsSnapshot, SettingChange) -> Unit>()

    @Volatile
    private var suspectRevertPending: SettingsSnapshot? = null

    private val hostListener: java.util.function.Consumer<ConfigHelper> =
        java.util.function.Consumer { external ->
            onExternalConfigChanged(external)
        }

    @Volatile
    private var closed = false

    @Volatile
    private var lastExternalLogMs = 0L

    private val poller: java.util.concurrent.ScheduledExecutorService =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "CDL settings sync").apply { isDaemon = true }
        }

    init {
        val loaded = store.load()
        live = loaded
        saved = loaded
        version = 1L
    }

    private fun attachHostListener() {

        runCatching { configManager.addConfigChangeListener(CONFIG_FILE, hostListener) }
            .onFailure {  }
    }

    private fun startPolling() {
        runCatching {
            poller.scheduleWithFixedDelay(
                { runCatching { pollOnce() }.onFailure {

                } },
                POLL_INTERVAL_MS,
                POLL_INTERVAL_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
        }.onFailure {  }
    }

    private fun pollOnce() {
        if (closed) return
        val fresh = store.reloadIfChanged()
        if (fresh == null) {

            resolveHeldSuspect()
            return
        }
        if (fresh.changedKeysAgainst(saved).isEmpty()) {

            clearSuspectRevertPending("文件轮询：读回与已落盘态一致")
            return
        }

        val adopt = holdIfSuspectRevert(saved, fresh, "文件轮询") { store.reloadAndLoad() } ?: return

        restore(adopt, SettingChange.Kind.EXTERNAL)
    }

    private fun resolveHeldSuspect() {
        val held = suspectRevertPending ?: return
        val now = store.reloadAndLoad() ?: return
        if (now.changedKeysAgainst(held).isEmpty()) {
            suspectRevertPending = null
            saved = held
            restore(held, SettingChange.Kind.EXTERNAL)
        } else {
            clearSuspectRevertPending("文件轮询：复核显示它不是当前配置")
        }
    }

    fun subscribe(listener: (SettingsSnapshot, SettingChange) -> Unit): AutoCloseable {
        subscribers += listener
        return AutoCloseable { subscribers.remove(listener) }
    }

    fun publish(delta: Map<String, Any?>): SettingChange {
        val before = live
        val overrides = coerceDelta(delta)
        if (overrides.isEmpty()) {

            return SettingChange.empty(before)
        }
        val next = before.withAll(overrides)
        live = next
        version++
        val change = SettingChange(
            kind = SettingChange.Kind.PUBLISH,
            changedKeys = before.changedKeysAgainst(next),
            preview = resolvePreview(overrides.keys),
        )
        notifySubscribers(next, change)
        return change
    }

    fun apply(
        delta: Map<String, Any?> = emptyMap(),
        kind: SettingChange.Kind = SettingChange.Kind.APPLY,
    ): SettingChange {
        val change = publish(delta)

        val ok = store.save(live)

        if (ok) saved = live
        if (!ok) {

            runCatching {
                WorkshopApi.ui.toast("词岛设置未能写入磁盘（内存已生效）", WorkshopApi.Ui.ToastType.Warning)
            }
        }
        return change.copy(kind = kind)
    }

    fun restore(snapshot: SettingsSnapshot, kind: SettingChange.Kind = SettingChange.Kind.UNDO): SettingChange {
        val before = live
        val changed = before.changedKeysAgainst(snapshot)
        if (changed.isEmpty()) return SettingChange.empty(before)
        live = snapshot
        version++
        val change = SettingChange(
            kind = kind,
            changedKeys = changed,

            preview = PreviewKind.IMMEDIATE,
        )

        if (kind == SettingChange.Kind.EXTERNAL) {
            val now = System.currentTimeMillis()
            if (now - lastExternalLogMs >= EXTERNAL_LOG_INTERVAL_MS) {
                lastExternalLogMs = now
                val shown = changed.take(MAX_LOGGED_KEYS)
                val more = if (changed.size > shown.size) " …共 ${changed.size} 项" else ""
            }
        }
        notifySubscribers(snapshot, change)
        return change
    }

    val isDirty: Boolean
        get() = live.changedKeysAgainst(saved).isNotEmpty()

    fun dataDirectory(): java.nio.file.Path? =
        runCatching { store.configPath()?.parent }.getOrNull()

    fun discardUnsaved(): SettingChange = restore(saved, SettingChange.Kind.DISCARD)

    private fun clearSuspectRevertPending(reason: String) {
        val had = suspectRevertPending ?: return
        suspectRevertPending = null
    }

    private fun holdIfSuspectRevert(
        previous: SettingsSnapshot,
        fresh: SettingsSnapshot,
        source: String,
        reread: () -> SettingsSnapshot?,
    ): SettingsSnapshot? {
        val changed = fresh.changedKeysAgainst(previous)

        val suspect = changed.size >= RESET_SUSPECT_MIN_KEYS
        if (!suspect) {

            clearSuspectRevertPending("$source：本次读取不是整份回退")
            return fresh
        }

        val again = runCatching { reread() }.getOrNull()
        if (again == null) {

            if (suspectRevertPending == null) {
                suspectRevertPending = fresh
                val shown = changed.take(8).joinToString(", ")
                val more = if (changed.size > 8) " 等 ${changed.size} 项" else ""
            }
            return null
        }
        if (again.changedKeysAgainst(fresh).isNotEmpty()) {

            clearSuspectRevertPending("$source：复读结果与可疑快照不一致")
            return again
        }

        clearSuspectRevertPending("$source：复读与可疑快照一致，确认为真实变更")
        return fresh
    }

    private fun onExternalConfigChanged(external: ConfigHelper) {
        val externalSnapshot = runCatching { SettingsStore(external).load() }.getOrNull() ?: return

        val changed = externalSnapshot.changedKeysAgainst(saved)
        if (changed.isEmpty()) {

            clearSuspectRevertPending("宿主推送：读回与已落盘态一致")
            return
        }

        val adopt = holdIfSuspectRevert(saved, externalSnapshot, "宿主推送") {

            SettingsStore(external).reloadAndLoad()
        } ?: return

        saved = adopt
        restore(adopt, SettingChange.Kind.EXTERNAL)
    }

    override fun close() {

        closed = true
        poller.shutdownNow()
        subscribers.clear()
        runCatching { configManager.removeConfigChangeListener(hostListener) }
    }

    private fun coerceDelta(delta: Map<String, Any?>): Map<String, Any> {
        if (delta.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, Any>(delta.size)
        for ((key, value) in delta) {
            val descriptor = SettingRegistry.descriptor(key)
            if (descriptor == null) {
                continue
            }
            if (value == null) continue
            val coerced = runCatching { descriptor.coerce(value) }.getOrNull() ?: continue

            if (coerced != live.raw(key)) result[key] = coerced
        }
        return result
    }

    private fun resolvePreview(keys: Set<String>): PreviewKind {
        var result = PreviewKind.IMMEDIATE
        for (key in keys) {
            when (SettingRegistry.descriptor(key)?.preview) {
                PreviewKind.DEFERRED -> return PreviewKind.DEFERRED
                PreviewKind.THROTTLED -> result = PreviewKind.THROTTLED
                else -> Unit
            }
        }
        return result
    }

    private fun notifySubscribers(snapshot: SettingsSnapshot, change: SettingChange) {
        for (subscriber in subscribers) {

            runCatching { subscriber(snapshot, change) }
                .onFailure {  }
        }
    }

    companion object {

        const val RESET_SUSPECT_MIN_KEYS = 5

        const val CONFIG_FILE: String = "cdl.json"

        val COLOR_PREFIXES: List<String> = listOf(
            "main_lyric.fill.color",
            "main_lyric.fill.unsung",
            "main_lyric.translation.color",
        )

        const val POLL_INTERVAL_MS: Long = 250L

        const val EXTERNAL_LOG_INTERVAL_MS: Long = 1000L

        const val MAX_LOGGED_KEYS: Int = 6

        internal fun create(context: PluginContext): SettingsRuntime? {
            val manager = runCatching { WorkshopApi.manager.createConfigManager() }
                .getOrElse {
                    return null
                }

            val helper = runCatching { manager.getConfig(CONFIG_FILE) }
                .getOrElse {
                    return null
                }
            val runtime = SettingsRuntime(SettingsStore(helper), manager)
            runtime.attachHostListener()
            runtime.startPolling()
            val path = runtime.store.configPath()

            return runtime
        }
    }
}
