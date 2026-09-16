package gg.sona.afterimage.mc189

import gg.sona.afterimage.format.AfterimageFormat
import gg.sona.afterimage.protocol.Protocol
import gg.sona.afterimage.world.RecorderIdentity
import net.minecraft.client.Minecraft
import java.nio.file.Path

class MinecraftPlatform(val minecraft: Minecraft) {
    val root: Path = minecraft.gameDir.toPath().resolve("afterimage")
    val recordingsDirectory: Path = root.resolve("recordings")
    val clipsDirectory: Path = root.resolve("clips")
    val bakesDirectory: Path = root.resolve("bakes")
    val exportsDirectory: Path = root.resolve("exports")
    val projectsDirectory: Path = root.resolve("projects")
    val lutsDirectory: Path = root.resolve("luts")

    fun identity(): RecorderIdentity {
        val profile = minecraft.session?.profile
        return RecorderIdentity(profile?.id, profile?.name)
    }

    fun recordingMetadata(): Map<String, String> {
        val identity = identity()
        val metadata = LinkedHashMap<String, String>()
        identity.name?.let { metadata[AfterimageFormat.MetadataKeys.PLAYER_NAME] = it }
        identity.uuid?.let { metadata[AfterimageFormat.MetadataKeys.PLAYER_UUID] = it.toString() }
        minecraft.currentServerEntry?.ip?.let { metadata[AfterimageFormat.MetadataKeys.SERVER_ADDRESS] = it }
        metadata[AfterimageFormat.MetadataKeys.MINECRAFT_VERSION] = Protocol.MINECRAFT_VERSION
        metadata[AfterimageFormat.MetadataKeys.AFTERIMAGE_VERSION] = AfterimageRuntime.VERSION
        return metadata
    }

    fun runOnGameThread(action: () -> Unit) {
        minecraft.executeTask(Runnable { action() })
    }
}
