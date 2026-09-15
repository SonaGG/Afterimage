package gg.sona.afterimage.mc.common

import gg.sona.afterimage.core.log.AfterimageLog
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

class AfterimageSettings(private val file: Path) {

    private val logger = AfterimageLog.logger("Afterimage")
    private val values = Properties()
    private var dirty = false

    var autoRecord: Boolean by boolean("recording.auto", true)
    var keyframeIntervalSeconds: Int by int("recording.keyframeIntervalSeconds", 10)
    var recordingIndicator: Boolean by boolean("recording.indicator", true)
    var instantClipSeconds: Int by int("recording.instantClipSeconds", 30)
    var cameraSampleRate: Int by int("recording.cameraSampleRate", 120)
    var uiScale: Float by float("editor.uiScale", 0f)
    var matchRecordedViewDistance: Boolean by boolean("replay.matchRecordedViewDistance", true)
    var applyServerPacks: Boolean by boolean("replay.serverResourcePacks", true)

    operator fun get(key: String): String? = values.getProperty(key)

    operator fun set(key: String, value: String) {
        if (values.getProperty(key) == value) return
        values.setProperty(key, value)
        dirty = true
    }

    fun load() {
        if (!Files.exists(file)) return
        runCatching { Files.newBufferedReader(file).use { values.load(it) } }
            .onFailure { logger.warn("Afterimage could not read settings $file", it) }
    }

    fun saveIfDirty() {
        if (!dirty) return
        dirty = false
        runCatching {
            Files.createDirectories(file.toAbsolutePath().parent)
            Files.newBufferedWriter(file).use { values.store(it, "Afterimage settings") }
        }.onFailure { logger.warn("Afterimage could not write settings $file", it) }
    }

    private fun boolean(key: String, default: Boolean) =
        Setting(key, default, { it.toBooleanStrictOrNull() }, { it.toString() })

    private fun int(key: String, default: Int) = Setting(key, default, { it.toIntOrNull() }, { it.toString() })

    private fun float(key: String, default: Float) = Setting(key, default, { it.toFloatOrNull() }, { it.toString() })


    private inner class Setting<T : Any>(
        private val key: String,
        private val default: T,
        private val parse: (String) -> T?,
        private val format: (T) -> String
    ) {
        operator fun getValue(owner: Any?, property: kotlin.reflect.KProperty<*>): T =
            values.getProperty(key)?.let(parse) ?: default

        operator fun setValue(owner: Any?, property: kotlin.reflect.KProperty<*>, value: T) {
            val encoded = format(value)
            if (values.getProperty(key) == encoded) return
            values.setProperty(key, encoded)
            dirty = true
        }
    }
}
