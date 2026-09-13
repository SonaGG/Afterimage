package gg.sona.recast.mc

import gg.sona.recast.format.RecastFormat
import gg.sona.recast.protocol.Protocol
import gg.sona.recast.replay.state.shadow.RecorderIdentity
import net.minecraft.client.Minecraft
import java.nio.file.Path

class MinecraftPlatform(val minecraft: Minecraft) {
    val root: Path = minecraft.gameDir.toPath().resolve("recast")
    val recordingsDirectory: Path = root.resolve("recordings")
    val clipsDirectory: Path = root.resolve("clips")
    val bakesDirectory: Path = root.resolve("bakes")
    val exportsDirectory: Path = root.resolve("exports")
    val projectsDirectory: Path = root.resolve("projects")

    fun identity(): RecorderIdentity {
        val profile = minecraft.session?.profile
        return RecorderIdentity(profile?.id, profile?.name)
    }

    fun recordingMetadata(): Map<String, String> {
        val identity = identity()
        val metadata = LinkedHashMap<String, String>()
        identity.name?.let { metadata[RecastFormat.MetadataKeys.PLAYER_NAME] = it }
        identity.uuid?.let { metadata[RecastFormat.MetadataKeys.PLAYER_UUID] = it.toString() }
        minecraft.currentServerEntry?.ip?.let { metadata[RecastFormat.MetadataKeys.SERVER_ADDRESS] = it }
        metadata[RecastFormat.MetadataKeys.MINECRAFT_VERSION] = Protocol.MINECRAFT_VERSION
        metadata[RecastFormat.MetadataKeys.RECAST_VERSION] = RecastRuntime.VERSION
        return metadata
    }

    fun runOnGameThread(action: () -> Unit) {
        minecraft.executeTask(Runnable { action() })
    }
}
