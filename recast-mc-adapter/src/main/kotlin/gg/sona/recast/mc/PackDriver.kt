package gg.sona.recast.mc

import gg.sona.recast.editor.PackState
import net.minecraft.client.Minecraft
import org.apache.logging.log4j.LogManager

class PackDriver(private val minecraft: Minecraft) {
    private val logger = LogManager.getLogger("Recast")
    private var applied: List<String>? = null
    private var original: List<String>? = null
    private var lastAvailableScan = 0L
    private var availableCache: List<String> = emptyList()

    fun available(): List<String> {
        val now = System.nanoTime()
        if (now - lastAvailableScan > RESCAN_NANOS) {
            lastAvailableScan = now
            availableCache = runCatching {
                minecraft.resourcePacks.load()
                minecraft.resourcePacks.available.map { it.name }
            }.getOrDefault(availableCache)
        }
        return availableCache
    }

    fun active(): List<String> = applied ?: current()

    private fun current(): List<String> = minecraft.resourcePacks.applied.map { it.name }

    fun apply(state: PackState?) {
        val target = state?.packs ?: original ?: return
        if (target == active()) return
        if (original == null) original = current()
        applied = target
        reload(target)
    }

    private fun reload(names: List<String>) {
        val repository = minecraft.resourcePacks
        runCatching { repository.load() }
        val entries = repository.available
        val selected = names.mapNotNull { name -> entries.firstOrNull { it.name == name } }
        logger.info("Recast switching resource packs to {}", if (selected.isEmpty()) "default" else selected.joinToString { it.name })
        repository.apply(selected)
        minecraft.reloadResources()
    }

    fun restore() {
        val saved = original ?: return
        val changed = applied != null && applied != saved
        original = null
        applied = null
        if (changed) reload(saved)
    }

    private companion object {
        const val RESCAN_NANOS = 2_000_000_000L
    }
}
