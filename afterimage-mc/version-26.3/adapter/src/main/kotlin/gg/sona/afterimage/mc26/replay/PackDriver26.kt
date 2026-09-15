package gg.sona.afterimage.mc26.replay

import gg.sona.afterimage.editor.PackState
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory

class PackDriver26(private val minecraft: Minecraft) {
    private val logger = LoggerFactory.getLogger("Afterimage")
    private var applied: List<String>? = null
    private var original: List<String>? = null
    private var lastAvailableScan = 0L
    private var availableCache: List<String> = emptyList()

    fun available(): List<String> {
        val now = System.nanoTime()
        if (now - lastAvailableScan > RESCAN_NANOS) {
            lastAvailableScan = now
            availableCache = runCatching {
                minecraft.resourcePackRepository.reload()
                minecraft.resourcePackRepository.availableIds.toList()
            }.getOrDefault(availableCache)
        }
        return availableCache
    }

    fun active(): List<String> = applied ?: current()

    private fun current(): List<String> = minecraft.resourcePackRepository.selectedIds.toList()

    fun apply(state: PackState?) {
        val target = state?.packs ?: original ?: return
        if (target == active()) return
        if (original == null) original = current()
        applied = target
        reload(target)
    }

    private fun reload(names: List<String>) {
        val repository = minecraft.resourcePackRepository
        runCatching { repository.reload() }
        val available = repository.availableIds
        val selected = names.filter { it in available }
        logger.info("Afterimage switching resource packs to {}", if (selected.isEmpty()) "default" else selected)
        repository.setSelected(selected)
        minecraft.reloadResourcePacks()
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
