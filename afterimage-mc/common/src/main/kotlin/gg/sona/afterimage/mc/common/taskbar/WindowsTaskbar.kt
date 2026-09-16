package gg.sona.afterimage.mc.common.taskbar

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.COM.COMInvoker
import com.sun.jna.platform.win32.W32Errors
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT

class WindowsTaskbar(pointer: Pointer, private val hwnd: WinDef.HWND) : COMInvoker(), ITaskbar {

    init {
        this.pointer = pointer
        invokeNative(3)
    }

    private fun invokeNative(ventry: Int, vararg objects: Any) {
        val args = arrayOfNulls<Any>(objects.size + 1)
        args[0] = this.pointer
        System.arraycopy(objects, 0, args, 1, objects.size)
        if (W32Errors.FAILED(this._invokeNativeObject(ventry, args, WinNT.HRESULT::class.java) as WinNT.HRESULT)) {
            throw IllegalStateException("Failed to invoke vtable: $ventry")
        }
    }

    override fun close() {
        reset()
        invokeNative(2)
    }

    override fun reset() = invokeNative(10, hwnd, 0)

    override fun setProgress(count: Long, outOf: Long) = invokeNative(9, hwnd, count, outOf)

    override fun setPaused() = invokeNative(10, hwnd, 8)

    override fun setNormal() = invokeNative(10, hwnd, 2)
}
