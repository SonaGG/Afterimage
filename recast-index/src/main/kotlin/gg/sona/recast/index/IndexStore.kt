package gg.sona.recast.index

import gg.sona.recast.core.log.RecastLog
import gg.sona.recast.replay.source.ReplaySource
import gg.sona.recast.replay.state.shadow.RecorderIdentity
import java.nio.file.Path

class IndexStore {
    private val logger = RecastLog.logger("recast.index")

    @Volatile
    var builder: IndexBuilder? = null
        private set

    val progress: Double get() = builder?.progress ?: 0.0

    fun cancel() {
        builder?.cancel()
    }

    fun cachePath(recording: Path): Path = recording.resolveSibling(recording.fileName.toString() + ".idx")

    fun load(recording: Path, source: ReplaySource, identity: RecorderIdentity): ReplayIndex? {
        val key = runCatching { IndexKey.of(recording, source.header.sessionId) }.getOrNull()
        val cache = cachePath(recording)
        if (key != null) {
            val cached = IndexCodec.read(cache, key)
            if (cached != null) {
                logger.debug("Loaded index ${cache.fileName}: ${cached.tracks.size} tracks, ${cached.events.size} events")
                return cached
            }
        }
        val started = System.nanoTime()
        val builder = IndexBuilder(source, identity)
        this.builder = builder
        val index = try {
            builder.build()
        } finally {
            this.builder = null
        }
        if (index == null) return null
        logger.info(
            "Indexed ${recording.fileName}: ${index.tickCount} ticks, ${index.tracks.size} tracks, ${index.events.size} events, ${index.blocks.size} block changes in ${(System.nanoTime() - started) / 1_000_000} ms"
        )
        if (key != null) runCatching { IndexCodec.write(cache, key, index) }
            .onFailure { logger.warn("Could not write index cache ${cache.fileName}", it) }
        return index
    }
}
