package gg.sona.afterimage.mc.ui

import gg.sona.afterimage.gfx.Target
import gg.sona.afterimage.mc.McGfx.asTarget
import gg.sona.afterimage.mc.common.ui.WorkspaceHost
import gg.sona.afterimage.mc.common.ui.WorkspaceScreens
import net.minecraft.client.Minecraft

class McWorkspaceScreens(private val minecraft: Minecraft) : WorkspaceScreens {
    override val isWorkspaceOpen: Boolean get() = minecraft.screen is WorkspaceScreen

    override fun openWorkspace(host: WorkspaceHost) = minecraft.openScreen(WorkspaceScreen(host))

    override fun closeWorkspace() = minecraft.openScreen(null)

    override fun mainTarget(): Target = minecraft.renderTarget.asTarget()
}
