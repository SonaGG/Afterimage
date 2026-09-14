package gg.sona.afterimage.editor.host


interface RecordingControl {
    fun status(): RecordingStatus

    fun start(): Boolean

    fun stop(): Boolean

    fun mark(label: String)

    var autoRecord: Boolean

    var keyframeIntervalSeconds: Int

    var showIndicator: Boolean

    var instantClipSeconds: Int

    fun profileIds(): List<String>

    fun enabledProfiles(): Set<String>

    fun setProfileEnabled(id: String, enabled: Boolean)
}
