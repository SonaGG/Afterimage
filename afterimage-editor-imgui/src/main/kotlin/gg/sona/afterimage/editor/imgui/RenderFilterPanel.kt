package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.editor.host.VisualSettings
import imgui.ImGui
import imgui.type.ImString

class RenderFilterPanel(private val context: EditorContext) :
    DialogPanel("Render Filter", Icon.LAYERS, 420f, 560f) {

    private val entityQuery = ImString("", 64)
    private val particleQuery = ImString("", 64)

    override fun content(frame: FrameContext) {
        val visuals = context.visuals
        Widgets.wrappedText(
            "Hide whole categories of entities or particles from render, on top of anything hidden individually.",
            EditorTheme.TEXT_DIM.u32
        )
        ImGui.dummy(0f, EditorFonts.px(4f))
        if (ImGui.beginTabBar("render-filter-tabs")) {
            if (ImGui.beginTabItem("Entities")) {
                entityTab(visuals)
                ImGui.endTabItem()
            }
            if (ImGui.beginTabItem("Particles")) {
                particleTab(visuals)
                ImGui.endTabItem()
            }
            ImGui.endTabBar()
        }
    }

    private fun entityTab(visuals: VisualSettings) {
        filterHeader(entityQuery, "Search entity types", visuals.hiddenEntityTypes, MOB_NAMES.keys)
        val query = entityQuery.get().lowercase()
        if (ImGui.beginChild("entity-list", 0f, 0f, false)) {
            for ((id, name) in MOB_NAMES.entries.sortedBy { it.value }) {
                if (query.isNotEmpty() && !name.contains(query)) continue
                typeRow(id, name, visuals.hiddenEntityTypes)
            }
        }
        ImGui.endChild()
    }

    private fun particleTab(visuals: VisualSettings) {
        filterHeader(particleQuery, "Search particle types", visuals.hiddenParticleTypes, PARTICLE_NAMES.keys)
        val query = particleQuery.get().lowercase()
        if (ImGui.beginChild("particle-list", 0f, 0f, false)) {
            for ((id, name) in PARTICLE_NAMES.entries.sortedBy { it.value }) {
                if (query.isNotEmpty() && !name.contains(query)) continue
                typeRow(id, name, visuals.hiddenParticleTypes)
            }
        }
        ImGui.endChild()
    }

    private fun filterHeader(query: ImString, hint: String, hidden: MutableSet<Int>, all: Set<Int>) {
        Widgets.search("##search", query, hint, EditorFonts.px(220f))
        ImGui.sameLine()
        if (Widgets.smallButton("Show all")) hidden.clear()
        ImGui.sameLine()
        if (Widgets.smallButton("Hide all")) hidden.addAll(all)
        ImGui.separator()
    }

    private fun typeRow(id: Int, name: String, hidden: MutableSet<Int>) {
        val isHidden = id in hidden
        ImGui.pushID(id)
        try {
            Widgets.toggle("##vis", !isHidden)?.let {
                if (it) hidden.remove(id) else hidden.add(id)
            }
            ImGui.sameLine()
            ImGui.textUnformatted(name.replaceFirstChar { it.uppercase() })
        } finally {
            ImGui.popID()
        }
    }

    private companion object {
        val MOB_NAMES = mapOf(
            48 to "mob", 49 to "monster", 50 to "creeper", 51 to "skeleton", 52 to "spider", 53 to "giant",
            54 to "zombie", 55 to "slime", 56 to "ghast", 57 to "zombie pigman", 58 to "enderman",
            59 to "cave spider", 60 to "silverfish", 61 to "blaze", 62 to "magma cube", 63 to "ender dragon",
            64 to "wither", 65 to "bat", 66 to "witch", 67 to "endermite", 68 to "guardian", 90 to "pig",
            91 to "sheep", 92 to "cow", 93 to "chicken", 94 to "squid", 95 to "wolf", 96 to "mooshroom",
            97 to "snow golem", 98 to "ocelot", 99 to "iron golem", 100 to "horse", 101 to "rabbit", 120 to "villager",
        )
        val PARTICLE_NAMES = mapOf(
            0 to "explosion", 1 to "explosion (large)", 2 to "explosion (huge)", 3 to "firework spark",
            4 to "water bubble", 5 to "water splash", 6 to "water wake", 7 to "suspended", 8 to "suspended depth",
            9 to "critical hit", 10 to "magic critical hit", 11 to "smoke", 12 to "smoke (large)", 13 to "spell",
            14 to "spell (instant)", 15 to "spell (mob)", 16 to "spell (mob ambient)", 17 to "spell (witch)",
            18 to "drip water", 19 to "drip lava", 20 to "villager angry", 21 to "villager happy", 22 to "town aura",
            23 to "note", 24 to "portal", 25 to "enchantment table", 26 to "flame", 27 to "lava", 28 to "footstep",
            29 to "cloud", 30 to "redstone", 31 to "snowball poof", 32 to "snow shovel", 33 to "slime",
            34 to "heart", 35 to "barrier", 36 to "item crack", 37 to "block crack", 38 to "block dust",
            39 to "water drop", 40 to "item take", 41 to "mob appearance",
        )
    }
}
