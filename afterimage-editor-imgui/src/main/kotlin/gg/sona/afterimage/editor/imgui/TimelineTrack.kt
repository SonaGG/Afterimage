package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.editor.LaneKind
import gg.sona.afterimage.editor.ValueLane

enum class TrackGroup(val label: String) { KEYFRAMES("Keyframes"), EDIT("Edit"), RECORDING("Recording") }

class TrackSpec(
    val kind: LaneKind,
    val icon: Icon,
    val color: EditorTheme.Rgb,
    val group: TrackGroup,
    val hint: String,
    private val baseHeight: Float,
) {
    val label: String get() = kind.label

    val rowHeight: Float get() = EditorFonts.px(baseHeight)

    val valueLane: ValueLane? get() = ValueLane.forKind(kind)

    val muteable: Boolean get() = group == TrackGroup.KEYFRAMES

    val selectable: Boolean get() = group != TrackGroup.RECORDING || kind == LaneKind.MOMENTS

    companion object {
        val ALL: List<TrackSpec> = listOf(
            TrackSpec(
                LaneKind.CAMERA, Icon.PATH, EditorTheme.KEYFRAME_SMOOTH, TrackGroup.KEYFRAMES,
                "Camera path keyframes. Ctrl+K keys the current view at the playhead.", 34f
            ),
            TrackSpec(
                LaneKind.SPEED, Icon.GAUGE, EditorTheme.SUCCESS, TrackGroup.KEYFRAMES,
                "Speed ramps: slow down or speed up playback between keyframes.", 30f
            ),
            TrackSpec(
                LaneKind.FOV, Icon.APERTURE, EditorTheme.KEYFRAME_BEZIER, TrackGroup.KEYFRAMES,
                "FOV keyframes zoom the lens over time, independent of the camera path.", 30f
            ),
            TrackSpec(
                LaneKind.FOCUS, Icon.FOCUS, EditorTheme.MINT, TrackGroup.KEYFRAMES,
                "Focus keyframes rack the depth of field distance. Turn on Depth of field in the Look panel to see it.", 30f
            ),
            TrackSpec(
                LaneKind.TIME_OF_DAY, Icon.SUN, EditorTheme.WARNING, TrackGroup.KEYFRAMES,
                "Time of day keyframes drive the sun and lighting.", 30f
            ),
            TrackSpec(
                LaneKind.SHAKE, Icon.WAVE, EditorTheme.RECORD, TrackGroup.KEYFRAMES,
                "Shake keyframes ramp handheld camera shake in and out.", 30f
            ),
            TrackSpec(
                LaneKind.SHAKE_FREQUENCY, Icon.WAVE, EditorTheme.RECORD, TrackGroup.KEYFRAMES,
                "How fast the shake wobbles. Pair it with Shake for a rougher or smoother handheld feel.", 30f
            ),
            TrackSpec(
                LaneKind.FREEZE, Icon.SNOWFLAKE, EditorTheme.TIMECODE, TrackGroup.KEYFRAMES,
                "Freeze keyframes hold the replay still for a few seconds while the camera keeps moving.", 30f
            ),
            TrackSpec(
                LaneKind.VIEW, Icon.EYE, EditorTheme.ACCENT_TEXT, TrackGroup.KEYFRAMES,
                "View keyframes switch the camera mode and target from that point on.", 28f
            ),
            TrackSpec(
                LaneKind.TEXTURE_PACK, Icon.PACKAGE, EditorTheme.PURPLE, TrackGroup.KEYFRAMES,
                "Texture pack keyframes switch resource packs over time.", 28f
            ),
            TrackSpec(
                LaneKind.TIMELAPSE, Icon.FAST_FORWARD, EditorTheme.WARNING, TrackGroup.KEYFRAMES,
                "Timelapse skips jump the replay forward during playback and export.", 24f
            ),
            TrackSpec(
                LaneKind.CLIPS, Icon.FILM, EditorTheme.CLIP_SELECTED, TrackGroup.EDIT,
                "Saved clips: ranges of the replay you want to keep or export.", 30f
            ),
            TrackSpec(
                LaneKind.MARKERS, Icon.MARKER, EditorTheme.MARKER, TrackGroup.EDIT,
                "Markers label moments worth returning to. M adds one at the playhead.", 22f
            ),
            TrackSpec(
                LaneKind.EVENTS, Icon.CLOCK, EditorTheme.EVENT_OTHER, TrackGroup.RECORDING,
                "Recorded events: chat, deaths, damage, explosions.", 20f
            ),
            TrackSpec(
                LaneKind.PLAYERS, Icon.PERSON, EditorTheme.ACCENT_TEXT, TrackGroup.RECORDING,
                "One row per player with presence, health, kills and deaths.", 20f
            ),
            TrackSpec(
                LaneKind.WORLD, Icon.GLOBE, EditorTheme.KEYFRAME_LINEAR, TrackGroup.RECORDING,
                "Block changes, explosions, projectiles, sounds and dimension changes.", 18f
            ),
            TrackSpec(
                LaneKind.MOMENTS, Icon.BOOKMARK, EditorTheme.KEYFRAME_HOLD, TrackGroup.RECORDING,
                "Detected and kept moments: kills, clutches, fights, escapes.", 28f
            ),
        )

        private val byKind = ALL.associateBy { it.kind }

        fun of(kind: LaneKind): TrackSpec? = byKind[kind]
    }
}
