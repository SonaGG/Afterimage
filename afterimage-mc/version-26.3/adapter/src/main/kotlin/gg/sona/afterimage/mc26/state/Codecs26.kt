package gg.sona.afterimage.mc26.state

import gg.sona.afterimage.mc26.net.PacketIds26
import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.net.PacketDirection
import gg.sona.afterimage.net.VarInts
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import net.minecraft.client.multiplayer.ClientRegistryLayer
import net.minecraft.client.multiplayer.KnownPacksManager
import net.minecraft.client.multiplayer.RegistryDataCollector
import net.minecraft.core.RegistryAccess
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket
import net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks
import net.minecraft.network.protocol.configuration.ConfigurationProtocols
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket
import net.minecraft.network.protocol.game.GameProtocols
import net.minecraft.server.packs.repository.KnownPack
import net.minecraft.server.packs.resources.ResourceProvider
import net.minecraft.world.level.chunk.PalettedContainerFactory
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class Codecs26 {
    private var collector = RegistryDataCollector()
    private var selectedPacks: List<KnownPack>? = null
    private var configurationHash: Long = 0L
    private var play: PlayCodecs? = null

    class PlayCodecs(
        val registries: RegistryAccess.Frozen,
        val clientbound: StreamCodec<ByteBuf, Packet<*>>,
        val serverbound: StreamCodec<ByteBuf, Packet<*>>,
    ) {
        val containers: PalettedContainerFactory by lazy { PalettedContainerFactory.create(registries) }
    }

    val ready: Boolean get() = play != null

    val registries: RegistryAccess.Frozen? get() = play?.registries

    val containers: PalettedContainerFactory? get() = play?.containers

    fun reset() {
        collector = RegistryDataCollector()
        selectedPacks = null
        configurationHash = 0L
        play = null
    }

    fun observeConfiguration(packet: CapturedPacket): Packet<*>? {
        if (packet.direction != PacketDirection.CLIENTBOUND) return null
        configurationHash = configurationHash * 31 + Retained.hashOf(packet)
        val decoded = try {
            ConfigurationProtocols.CLIENTBOUND.codec().decode(FriendlyByteBuf(frame(packet)))
        } catch (error: Exception) {
            logger.debug("Afterimage could not decode configuration packet {}", packet, error)
            return null
        }
        when (decoded) {
            is ClientboundRegistryDataPacket -> collector.appendContents(decoded.registry(), decoded.entries())
            is ClientboundUpdateTagsPacket -> collector.appendTags(decoded.tags())
            is ClientboundSelectKnownPacks -> selectedPacks = decoded.knownPacks()
            is ClientboundFinishConfigurationPacket -> bind()
            else -> Unit
        }
        return decoded
    }

    private fun bind() {
        val key = configurationHash
        val cached = CACHE[key]
        if (cached != null) {
            play = cached
            collector = RegistryDataCollector()
            return
        }
        val built = synchronized(LOCK) {
            CACHE[key] ?: build()?.also { CACHE[key] = it }
        } ?: return
        play = built
        collector = RegistryDataCollector()
    }

    private fun build(): PlayCodecs? {
        val registries = try {
            val contents = collector.contentsCollector
            if (contents == null) ClientRegistryLayer.createRegistryAccess().compositeAccess()
            else {
                val packs = selectedPacks
                if (packs == null) collector.loadNewElementsAndTags(ResourceProvider.EMPTY, contents, false).freeze()
                else {
                    val manager = KNOWN_PACKS.value
                    manager.trySelectingPacks(packs)
                    manager.createResourceManager().use { collector.loadNewElementsAndTags(it, contents, false).freeze() }
                }
            }
        } catch (error: Exception) {
            logger.error("Afterimage could not build replay registries", error)
            return null
        }
        return PlayCodecs(
            registries,
            cast(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries)).codec()),
            cast(GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries), CONTEXT).codec()),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun cast(codec: StreamCodec<*, *>): StreamCodec<ByteBuf, Packet<*>> = codec as StreamCodec<ByteBuf, Packet<*>>

    fun decode(packet: CapturedPacket): Packet<*>? {
        val codecs = play ?: return null
        val codec = if (packet.direction == PacketDirection.CLIENTBOUND) codecs.clientbound else codecs.serverbound
        val buffer = frame(packet)
        return try {
            codec.decode(buffer)
        } catch (error: Exception) {
            logger.debug("Afterimage could not decode {}", packet, error)
            null
        } finally {
            buffer.release()
        }
    }

    fun encodeBytes(packet: Packet<*>): ByteArray? {
        val codecs = play ?: return null
        val buffer = Unpooled.buffer(256)
        try {
            codecs.clientbound.encode(buffer, packet)
            val bytes = ByteArray(buffer.readableBytes())
            buffer.getBytes(buffer.readerIndex(), bytes)
            return bytes
        } catch (error: Exception) {
            logger.warn("Afterimage could not encode {}", packet.javaClass.simpleName, error)
            return null
        } finally {
            buffer.release()
        }
    }

    fun encode(packet: Packet<*>, nanos: Long): CapturedPacket? {
        val bytes = encodeBytes(packet) ?: return null
        val packed = VarInts.read(bytes, 0, bytes.size)
        val idLength = VarInts.length(packed)
        return CapturedPacket(PacketDirection.CLIENTBOUND, nanos, VarInts.value(packed), bytes.copyOfRange(idLength, bytes.size))
    }

    fun same(a: Packet<*>?, b: Packet<*>?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        if (a.javaClass !== b.javaClass) return false
        val left = encodeBytes(a) ?: return false
        val right = encodeBytes(b) ?: return false
        return left.contentEquals(right)
    }

    fun frame(packet: CapturedPacket): ByteBuf {
        val buffer = Unpooled.buffer(packet.payload.size + 5)
        var remaining = PacketIds26.wireId(packet.packetId)
        while (remaining and -0x80 != 0) {
            buffer.writeByte((remaining and 0x7F) or 0x80)
            remaining = remaining ushr 7
        }
        buffer.writeByte(remaining)
        buffer.writeBytes(packet.payload)
        return buffer
    }

    companion object {
        private val logger = LoggerFactory.getLogger("Afterimage")
        private val LOCK = Any()
        private val CACHE = ConcurrentHashMap<Long, PlayCodecs>()
        private val KNOWN_PACKS = lazy { KnownPacksManager() }

        val CONTEXT = object : GameProtocols.Context {
            override fun hasInfiniteMaterials(): Boolean = true
            override fun canUseCommandBlocks(): Boolean = true
        }
    }
}
