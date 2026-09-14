package gg.sona.recast.mc

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import java.nio.ByteBuffer

class ScratchTexture {
    var id = 0
        private set
    var width = 0
        private set
    var height = 0
        private set

    fun ensure(width: Int, height: Int) {
        if (id != 0 && this.width == width && this.height == height) return
        destroy()
        id = GL11.glGenTextures()
        this.width = width
        this.height = height
        val previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null as ByteBuffer?)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous)
    }

    fun copyFromReadFramebuffer(x: Int, y: Int, width: Int, height: Int) {
        ensure(width, height)
        val previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id)
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, x, y, width, height)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous)
    }

    fun destroy() {
        if (id != 0) GL11.glDeleteTextures(id)
        id = 0
        width = 0
        height = 0
    }
}
