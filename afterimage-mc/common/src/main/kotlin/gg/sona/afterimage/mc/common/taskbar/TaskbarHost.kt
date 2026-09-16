package gg.sona.afterimage.mc.common.taskbar

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.*
import com.sun.jna.ptr.PointerByReference
import gg.sona.afterimage.core.log.AfterimageLog
import gg.sona.afterimage.mc.common.SdlWindow

object TaskbarHost {
    private val LOGGER = AfterimageLog.logger("Afterimage/Taskbar")
    private val isWindows = System.getProperty("os.name", "").lowercase().contains("win")

    fun create(): ITaskbar {
        if (!isWindows) return NoopTaskbar
        return try {
            createWindowsInterface()
        } catch (error: Throwable) {
            LOGGER.warn("Unable to create Windows taskbar interface, export progress won't show there", error)
            NoopTaskbar
        }
    }

    private fun createWindowsInterface(): ITaskbar {
        val pointerRef = PointerByReference()
        val hr = Ole32.INSTANCE.CoCreateInstance(
            Guid.GUID("56FDF344-FD6D-11d0-958A-006097C9A090"),
            null,
            WTypes.CLSCTX_SERVER,
            Guid.GUID("EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF"),
            pointerRef,
        )
        if (W32Errors.FAILED(hr)) throw IllegalStateException("Failed to create ITaskbarList3 (hr=$hr)")
        val hwnd = WinDef.HWND(Pointer(SdlWindow.nativeHandle()))
        return WindowsTaskbar(pointerRef.value, hwnd)
    }
}
