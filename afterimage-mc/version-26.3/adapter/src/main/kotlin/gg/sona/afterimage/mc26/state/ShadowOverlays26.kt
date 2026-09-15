package gg.sona.afterimage.mc26.state

import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.protocol.OverlayChatLine
import gg.sona.afterimage.protocol.OverlayReset
import net.minecraft.core.HolderLookup
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket

class ChatLine26(val nanos: Long, val json: String)

class TitleState26(val titleJson: String?, val subtitleJson: String?, val fadeIn: Int, val stay: Int, val fadeOut: Int, val shownAtNanos: Long) {
    val totalNanos: Long get() = (fadeIn + stay + fadeOut).toLong() * Nanos.PER_TICK

    fun activeAt(nanos: Long): Boolean = titleJson != null && nanos >= shownAtNanos && nanos < shownAtNanos + totalNanos
}

class ShadowOverlays26 {
    private val chat = ArrayDeque<ChatLine26>()
    var actionBar: ChatLine26? = null
        private set
    private var titleJson: String? = null
    private var subtitleJson: String? = null
    private var fadeIn = DEFAULT_FADE_IN
    private var stay = DEFAULT_STAY
    private var fadeOut = DEFAULT_FADE_OUT
    private var titleShownAt = Long.MIN_VALUE

    val title: TitleState26 get() = TitleState26(titleJson, subtitleJson, fadeIn, stay, fadeOut, titleShownAt)

    fun visibleChat(nanos: Long, windowNanos: Long = CHAT_VISIBLE_NANOS): List<ChatLine26> = chat.filter { it.nanos <= nanos && nanos - it.nanos <= windowNanos }

    fun visibleActionBar(nanos: Long): ChatLine26? = actionBar?.takeIf { it.nanos <= nanos && nanos - it.nanos <= ACTION_BAR_NANOS }

    fun apply(packet: Packet<*>, nanos: Long, registries: HolderLookup.Provider?): Boolean {
        when (packet) {
            is ClientboundSystemChatPacket -> if (packet.overlay()) actionBar(packet.content(), nanos, registries) else line(packet.content(), nanos, registries)
            is ClientboundPlayerChatPacket -> line(decorate(packet), nanos, registries)
            is ClientboundDisguisedChatPacket -> line(packet.chatType().decorate(packet.message()), nanos, registries)
            is ClientboundSetActionBarTextPacket -> actionBar(packet.text(), nanos, registries)
            is ClientboundSetTitleTextPacket -> {
                titleJson = Components26.json(packet.text(), registries)
                titleShownAt = nanos
            }

            is ClientboundSetSubtitleTextPacket -> subtitleJson = Components26.json(packet.text(), registries)
            is ClientboundSetTitlesAnimationPacket -> {
                val active = title.activeAt(nanos)
                if (packet.fadeIn >= 0) fadeIn = packet.fadeIn
                if (packet.stay >= 0) stay = packet.stay
                if (packet.fadeOut >= 0) fadeOut = packet.fadeOut
                if (active) titleShownAt = nanos
            }

            is ClientboundClearTitlesPacket -> {
                titleJson = null
                subtitleJson = null
                titleShownAt = Long.MIN_VALUE
                if (packet.shouldResetTimes()) {
                    fadeIn = DEFAULT_FADE_IN
                    stay = DEFAULT_STAY
                    fadeOut = DEFAULT_FADE_OUT
                }
            }

            else -> return false
        }
        return true
    }

    fun apply(packet: OverlayReset, nanos: Long) {
        if (packet.resetsChat) {
            chat.clear()
            for ((ageNanos, json) in packet.chat.sortedByDescending { it.ageNanos }) chat.addLast(ChatLine26(nanos - ageNanos,
                json
            ))
        }
        if (packet.resetsTitle) {
            titleShownAt = if (packet.titleAgeNanos < 0 || titleJson == null) Long.MIN_VALUE else nanos - packet.titleAgeNanos
        }
        if (packet.resetsActionBar) {
            val bar = actionBar
            actionBar = if (packet.actionBarAgeNanos < 0 || bar == null) null else ChatLine26(nanos - packet.actionBarAgeNanos, bar.json)
        }
    }

    fun snapshot(nanos: Long, registries: HolderLookup.Provider?, vanilla: MutableList<Packet<*>>): OverlayReset {
        val lines = chat.filter { it.nanos <= nanos }
        val bar = visibleActionBar(nanos)
        val current = title
        val titleActive = current.activeAt(nanos)
        bar?.let { line -> Components26.parse(line.json, registries)?.let { vanilla += ClientboundSetActionBarTextPacket(it) } }
        if (titleActive) {
            vanilla += ClientboundSetTitlesAnimationPacket(current.fadeIn, current.stay, current.fadeOut)
            vanilla += ClientboundSetSubtitleTextPacket(current.subtitleJson?.let { Components26.parse(it, registries) } ?: Component.empty())
            current.titleJson?.let { json -> Components26.parse(json, registries)?.let { vanilla += ClientboundSetTitleTextPacket(it) } }
        }
        return OverlayReset(
            OverlayReset.CHAT or OverlayReset.TITLE or OverlayReset.ACTION_BAR,
            lines.map { OverlayChatLine(nanos - it.nanos, it.json) },
            if (titleActive) nanos - current.shownAtNanos else OverlayReset.NONE,
            if (bar != null) nanos - bar.nanos else OverlayReset.NONE,
        )
    }

    fun clear() {
        chat.clear()
        actionBar = null
        titleJson = null
        subtitleJson = null
        fadeIn = DEFAULT_FADE_IN
        stay = DEFAULT_STAY
        fadeOut = DEFAULT_FADE_OUT
        titleShownAt = Long.MIN_VALUE
    }

    private fun line(component: Component, nanos: Long, registries: HolderLookup.Provider?) {
        chat.addLast(ChatLine26(nanos, Components26.json(component, registries)))
        while (chat.size > MAX_CHAT) chat.removeFirst()
    }

    private fun actionBar(component: Component, nanos: Long, registries: HolderLookup.Provider?) {
        actionBar = ChatLine26(nanos, Components26.json(component, registries))
    }

    companion object {
        const val MAX_CHAT = 100
        const val DEFAULT_FADE_IN = 10
        const val DEFAULT_STAY = 70
        const val DEFAULT_FADE_OUT = 20
        val CHAT_VISIBLE_NANOS: Long = Nanos.ofSeconds(10)
        val ACTION_BAR_NANOS: Long = Nanos.ofSeconds(3)

        fun decorate(packet: ClientboundPlayerChatPacket): Component =
            packet.chatType().decorate(packet.unsignedContent().orElse(null) ?: Component.literal(packet.body().content()))
    }
}
