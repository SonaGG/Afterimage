package gg.sona.afterimage.mc.common.ui

import gg.sona.afterimage.gfx.Target

interface WorkspaceScreens {
    val isWorkspaceOpen: Boolean
    fun openWorkspace(host: WorkspaceHost)
    fun closeWorkspace()
    fun mainTarget(): Target?
}