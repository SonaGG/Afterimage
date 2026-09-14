package gg.sona.afterimage.mc

import org.apache.logging.log4j.LogManager
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL14
import java.nio.ByteBuffer

class DepthTexture {
    var id = 0
        private set
    var width = 0
        private set
    var height = 0
        private set
    var failed = false
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
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_DEPTH_TEXTURE_MODE, GL11.GL_LUMINANCE)
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, width, height, 0,
            GL11.GL_DEPTH_COMPONENT, GL11.GL_UNSIGNED_INT, null as ByteBuffer?
        )
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous)
    }

    fun copyFromReadFramebuffer(x: Int, y: Int, width: Int, height: Int): Boolean {
        if (failed) return false
        ensure(width, height)
        val previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id)
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {
        }
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, x, y, width, height)
        val error = GL11.glGetError()
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous)
        if (error != GL11.GL_NO_ERROR) {
            failed = true
            LogManager.getLogger("Afterimage").warn("(Afterimage) depth capture disabled: GL error 0x{}", Integer.toHexString(error))
            return false
        }
        return true
    }

    fun destroy() {
        if (id != 0) GL11.glDeleteTextures(id)
        id = 0
        width = 0
        height = 0
    }
}
