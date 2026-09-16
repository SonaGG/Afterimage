package gg.sona.afterimage.mc26.replay

import gg.sona.afterimage.camera.*
import gg.sona.afterimage.camera.impl.*
import gg.sona.afterimage.camera.shake.ShakeOffset
import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.editor.ViewState
import gg.sona.afterimage.editor.host.CameraControl
import gg.sona.afterimage.mc.common.ExportCamera
import gg.sona.afterimage.mc.common.SceneCamera
import gg.sona.afterimage.mc.common.SceneCameraHost
import gg.sona.afterimage.mc.common.SwingState
import gg.sona.afterimage.mc26.mixin.CameraAccessor
import gg.sona.afterimage.mc26.mixin.HudAccessor
import gg.sona.afterimage.mc26.mixin.KeyMappingAccessor
import gg.sona.afterimage.mc26.mixin.LivingEntityAccessor
import gg.sona.afterimage.net.PackedPosition
import gg.sona.afterimage.protocol.LocalScreen
import gg.sona.afterimage.replay.session.ReplaySession
import gg.sona.afterimage.world.interpolation.CatmullRomInterpolation
import gg.sona.afterimage.world.interpolation.Interpolation
import gg.sona.afterimage.world.interpolation.LinearInterpolation
import gg.sona.afterimage.world.interpolation.SnapInterpolation
import net.minecraft.client.Camera
import net.minecraft.client.CameraType
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.exp

class CameraDriver26(private val minecraft: Minecraft, private val picker: ViewportPicker26) : CameraControl, ExportCamera {
    override val settings = CameraSettings()
    val scene = SceneCamera(SceneHost(), settings)
    val entitySmoother = EntitySmoother26(minecraft, settings)
    val rig = CameraRig(FreeCamera())
    var path: CameraPath? = null
    var pathProvider: (Long) -> CameraPose? = { null }
    var fovProvider: (Long) -> Double? = { null }

    @Volatile
    override var exportPose: CameraPose? = null
    var previewPose: CameraPose? = null
    override var exporting: Boolean = false

    @Volatile
    override var exportFov: Float = 0f

    @Volatile
    override var exportOrthoScale: Float? = null
    var shakeProvider: (Long) -> Double? = { null }
    var shakeFrequencyProvider: (Long) -> Double? = { null }

    @Volatile
    override var pathActive: Boolean = false
    override var pathSuspended: Boolean = false
        private set
    private var suspendedAtNanos = 0L

    @Volatile
    var detached: Boolean = false
        private set
    val hiddenEntityId: Int
        get() = when {
            settings.mode != CameraMode.FIRST_PERSON || !settings.hideTargetInFirstPerson -> Int.MIN_VALUE
            settings.targetsRecorder() -> session?.shadow26?.localPlayer?.entityId ?: Int.MIN_VALUE
            else -> settings.targetEntityId
        }
    var recorderPerspective: Int = 0
        private set
    var recordedHud: () -> LocalScreen? = { null }
    private var cameraEntity: ReplayCameraEntity26? = null
    private var viewEntity: Entity? = null
    private var lastPose: CameraPose = CameraPose.ORIGIN
    private var smoothedPose: CameraPose? = null
    private var session: ReplaySession? = null
    private var tickDelta = 1f
    private var pendingMatrix: FloatArray? = null
    private var pendingHand: FloatArray? = null
    private var pendingFov = 0f
    private var previousHideGui = false
    private var previousPerspective = CameraType.FIRST_PERSON
    private var hideGuiManaged = false
    private var playerListForced = false
    private val handOverride = Matrix4f()

    private val recorderPose: PoseSource = PoseSource { nanos ->
        val replay = session ?: return@PoseSource null
        val pose = replay.shadow26.localPlayer.poseAt(nanos, interpolation())
        CameraPose(Vector3d(pose.x, pose.y + settings.eyeHeight, pose.z), Rotation(pose.yaw.toDouble(), pose.pitch.toDouble(), settings.roll), effectiveFov())
    }

    private inner class SceneHost : SceneCameraHost {
        override fun playerEyePose(): CameraPose? {
            val player = minecraft.player ?: return null
            return CameraPose(Vector3d(player.x, player.y + player.eyeHeight, player.z), Rotation(player.yRot.toDouble(), player.xRot.toDouble(), settings.roll), CameraPose.DEFAULT_FOV)
        }

        override fun applyPose(pose: CameraPose) {
            val player = minecraft.player ?: return
            player.absSnapTo(pose.position.x, pose.position.y - player.eyeHeight, pose.position.z, pose.rotation.yaw.toFloat(), pose.rotation.pitch.toFloat())
            player.setOldPosAndRot()
        }

        override fun rayHitDistance(origin: Vector3d, direction: Vector3d, maxDistance: Double): Double? {
            val level = minecraft.level ?: return null
            val player = minecraft.player ?: return null
            val end = Vector3d(origin).add(Vector3d(direction).mul(maxDistance))
            val hit = level.clip(ClipContext(Vec3(origin.x, origin.y, origin.z), Vec3(end.x, end.y, end.z), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
            if (hit.type != HitResult.Type.BLOCK) return null
            val distance = Vector3d(hit.location.x, hit.location.y, hit.location.z).distance(origin)
            return if (distance > 0.05) distance else null
        }

        override fun ray(normalizedX: Float, normalizedY: Float): Vector3d? = picker.ray(normalizedX, normalizedY)
    }

    fun attach(replay: ReplaySession) {
        session = replay
        settings.mode = CameraMode.FREE
        settings.targetEntityId = CameraSettings.TARGET_RECORDER
        previousHideGui = minecraft.gui.hud.isHidden
        previousPerspective = minecraft.options.cameraType
        hideGuiManaged = true
        apply()
    }

    fun detach() {
        session = null
        release()
        detached = false
        pathActive = false
        recorderPerspective = 0
        pendingMatrix = null
        pendingHand = null
        if (hideGuiManaged) {
            (minecraft.gui.hud as HudAccessor).afterimage_setHidden(previousHideGui)
            minecraft.options.cameraType = previousPerspective
            hideGuiManaged = false
        }
        if (playerListForced) {
            playerListForced = false
            KeyMapping.set((minecraft.options.keyPlayerList as KeyMappingAccessor).afterimage_key(), false)
        }
    }

    private val exactRecorderView: Boolean
        get() = settings.mode == CameraMode.FIRST_PERSON && settings.targetsRecorder() && settings.exactFirstPerson

    override fun apply() {
        rig.behavior = buildBehavior()
        smoothedPose = null
        val firstPersonOfOther = settings.mode == CameraMode.FIRST_PERSON && !settings.targetsRecorder()
        detached = settings.mode != CameraMode.FREE && !firstPersonOfOther
        if (settings.mode == CameraMode.FREE) {
            if (minecraft.cameraEntity !== minecraft.player && minecraft.player != null) teleport(lastPose)
            release()
        }
    }

    override fun bakePoses(fromNanos: Long, toNanos: Long, stepNanos: Long): List<CameraPose> {
        if (settings.mode == CameraMode.FREE || session == null || stepNanos <= 0L || toNanos <= fromNanos) return emptyList()
        val behavior = buildBehavior()
        val poses = ArrayList<CameraPose>()
        var time = fromNanos
        var first = true
        while (time <= toNanos) {
            if (!first) behavior.update(stepNanos)
            first = false
            val pose = if (settings.mode == CameraMode.FIRST_PERSON && settings.targetsRecorder()) recorderPose.poseAt(time) else behavior.poseAt(time)
            poses += pose ?: CameraPose.ORIGIN
            time += stepNanos
        }
        return poses
    }

    private fun buildBehavior(): CameraBehavior {
        val target = targetSource()
        return when (settings.mode) {
            CameraMode.FREE -> FreeCamera(currentPose())
            CameraMode.FIRST_PERSON -> FirstPersonCamera(target)
            CameraMode.ORBIT -> OrbitCamera(target).also {
                it.distance = settings.orbitDistance
                it.pitch = settings.orbitPitch
                it.yawOffset = settings.orbitYawOffset
                it.heightOffset = settings.orbitHeight
                it.degreesPerSecond = settings.orbitDegreesPerSecond
                it.fov = effectiveFov()
            }

            CameraMode.FOLLOW -> FollowCamera(target).also {
                it.offset = Vector3d(settings.followOffsetX, settings.followOffsetY, settings.followOffsetZ)
                it.lookAtTarget = settings.followLookAtTarget
                it.fov = effectiveFov()
            }

            CameraMode.CHASE -> ChaseCamera(target).also {
                it.distance = settings.chaseDistance
                it.height = settings.chaseHeight
                it.stiffness = settings.chaseStiffness
                it.damping = settings.chaseDamping
                it.fov = effectiveFov()
            }
        }
    }

    override fun exportPoseAt(nanos: Long): CameraPose {
        val replay = session ?: return lastPose
        pathProvider(nanos)?.let { return withShake(it, nanos) }
        if (settings.mode == CameraMode.FIRST_PERSON && settings.targetsRecorder()) {
            (thirdPersonPose(replay, nanos) ?: recorderPose.poseAt(nanos))?.let { return it }
        }
        return currentPose()
    }

    private fun recorderViewPerspective(replay: ReplaySession): Int =
        if (settings.mode == CameraMode.FIRST_PERSON && settings.targetsRecorder()) replay.shadow26.localPlayer.screen.perspective else 0

    private fun thirdPersonPose(replay: ReplaySession, nanos: Long): CameraPose? {
        val local = replay.shadow26.localPlayer
        if (local.screen.perspective == 0) return null
        val frame = local.cameraFrames.blended(nanos, settings.interpolation != PoseInterpolation.SNAP) ?: return null
        val view = Matrix4f().set(frame.modelView)
        val offset = view.invertAffine(Matrix4f()).transformPosition(Vector3f())
        val pitch = Math.toDegrees(asin(view.get(1, 2).toDouble().coerceIn(-1.0, 1.0)))
        val yaw = Rotation.shortestDelta(0.0, Math.toDegrees(atan2(-view.get(0, 2).toDouble(), view.get(2, 2).toDouble())) - 180.0)
        val roll = Math.toDegrees(atan2(-view.get(1, 0).toDouble(), view.get(1, 1).toDouble()))
        val pose = local.poseAt(nanos, interpolation())
        val feet = frame.position
        return CameraPose(
            Vector3d((feet?.get(0) ?: pose.x) + offset.x, (feet?.get(1) ?: pose.y) + offset.y, (feet?.get(2) ?: pose.z) + offset.z),
            Rotation(yaw, pitch, roll),
            if (settings.overrideFov) settings.fov else frame.fov.toDouble(),
        )
    }

    override fun currentPose(): CameraPose = if (pathActive || detached || viewEntity != null) lastPose else playerPose() ?: lastPose

    override fun teleport(pose: CameraPose) {
        val player = minecraft.player ?: return
        player.absSnapTo(pose.position.x, pose.position.y - player.eyeHeight, pose.position.z, pose.rotation.yaw.toFloat(), pose.rotation.pitch.toFloat())
        player.setOldPosAndRot()
        lastPose = pose
    }

    override fun detachedFromPlayer(): Boolean = exportPose != null || pathActive || detached || viewEntity != null

    override fun frame(x: Double, y: Double, z: Double, radius: Double) {
        if (settings.mode != CameraMode.FREE) {
            settings.mode = CameraMode.FREE
            apply()
        }
        scene.frame(Vector3d(x, y, z), radius, currentPose())
    }

    override fun snapView(yaw: Double, pitch: Double) {
        if (settings.mode != CameraMode.FREE) {
            settings.mode = CameraMode.FREE
            apply()
        }
        scene.snapView(Rotation(yaw, pitch, settings.roll), currentPose())
    }

    override val gestureActive: Boolean get() = scene.gesture != SceneCamera.Gesture.NONE

    override fun speedFlashUntilNanos(): Long = scene.speedFlashUntilNanos

    override fun recentGestureTravel(): Double = scene.recentGestureTravel()

    fun onFrame(deltaNanos: Long, wallDeltaNanos: Long, tickDelta: Float, workspaceOpen: Boolean, viewportHovered: Boolean, keyboardFree: Boolean = true) {
        recorderPerspective = 0
        placeCamera(deltaNanos, wallDeltaNanos, tickDelta, workspaceOpen, viewportHovered, keyboardFree)
        if (!hideGuiManaged) return
        val perspective = if (exportPose == null && previewPose == null) recorderPerspective else 0
        val type = CameraType.entries[perspective.coerceIn(0, CameraType.entries.size - 1)]
        if (minecraft.options.cameraType != type) minecraft.options.cameraType = type
    }

    private fun placeCamera(deltaNanos: Long, wallDeltaNanos: Long, tickDelta: Float, workspaceOpen: Boolean, viewportHovered: Boolean, keyboardFree: Boolean) {
        this.tickDelta = tickDelta
        val replay = session ?: return
        val level = minecraft.level ?: return
        pendingMatrix = null
        pendingHand = null
        if (hideGuiManaged) {
            val recorded = recordedHud()
            val hide = !(settings.mode == CameraMode.FIRST_PERSON && settings.showHud) || recorded?.hudHidden == true
            val hud = minecraft.gui.hud
            if (hud.isHidden != hide) (hud as HudAccessor).afterimage_setHidden(hide)
            val list = !hide && (settings.showPlayerList || recorded?.playerListShown == true)
            if (playerListForced != list) {
                playerListForced = list
                KeyMapping.set((minecraft.options.keyPlayerList as KeyMappingAccessor).afterimage_key(), list)
            }
        }
        exportPose?.let { pose ->
            if (pathProvider(replay.positionNanos) == null) recorderPerspective = recorderViewPerspective(replay)
            placeExport(pose, level)
            return
        }
        rig.shake.continuousAmplitude = shakeProvider(replay.positionNanos) ?: 0.0
        rig.shake.continuousFrequencyHz = shakeFrequencyProvider(replay.positionNanos) ?: settings.shakeFrequencyHz
        val pathPose = pathProvider(replay.positionNanos)
        if (pathPose != null && pathSuspended && (replay.playing || replay.positionNanos != suspendedAtNanos)) pathSuspended = false
        if (pathPose != null && !pathSuspended && settings.mode == CameraMode.FREE && workspaceOpen && !replay.playing && scene.wantsControl(viewportHovered, keyboardFree)) {
            pathSuspended = true
            suspendedAtNanos = replay.positionNanos
        }
        if (pathPose != null && !pathSuspended) {
            followPath(withShake(pathPose, replay.positionNanos), level)
            return
        }
        if (pathPose == null) pathSuspended = false
        if (pathActive) {
            pathActive = false
            if (settings.mode == CameraMode.FREE) {
                teleport(lastPose)
                release()
                scene.resetMotion()
            }
        }
        if (exactRecorderView && placeExactRecorderView(replay)) {
            recorderPerspective = replay.shadow26.localPlayer.screen.perspective
            return
        }
        if (settings.mode == CameraMode.FREE) {
            if (workspaceOpen) scene.onFrame(wallDeltaNanos, viewportHovered, keyboardFree) else scene.cancelGesture()
            playerPose()?.let { lastPose = it }
            return
        }
        if (settings.mode == CameraMode.FIRST_PERSON && !settings.targetsRecorder()) {
            val entity = level.getEntity(settings.targetEntityId)
            if (entity != null) {
                switchCamera(entity)
                viewEntity = entity
                lastPose = renderedPose(entity)
                mirrorViewRotation(lastPose)
                return
            }
        }
        rig.update(deltaNanos)
        val raw = rig.poseAt(replay.positionNanos) ?: return
        val pose = smooth(raw, deltaNanos)
        lastPose = pose
        if (settings.mode == CameraMode.FIRST_PERSON && settings.targetsRecorder()) {
            val player = minecraft.player ?: return
            placePlayerAsCamera(pose.position.x, pose.position.y - player.eyeHeight, pose.position.z, pose.rotation.yaw.toFloat(), pose.rotation.pitch.toFloat())
            recorderPerspective = replay.shadow26.localPlayer.screen.perspective
            return
        }
        val camera = cameraEntity(level)
        camera.place(pose)
        viewEntity = null
        switchCamera(camera)
    }

    private fun switchCamera(entity: Entity) {
        if (minecraft.cameraEntity === entity) return
        minecraft.cameraEntity = entity
        val camera = minecraft.gameRenderer.mainCamera() as CameraAccessor
        camera.afterimage_setEyeHeight(entity.eyeHeight)
        camera.afterimage_setEyeHeightOld(entity.eyeHeight)
    }

    private fun cameraEntity(level: ClientLevel): ReplayCameraEntity26 =
        cameraEntity?.takeIf { it.level() === level } ?: ReplayCameraEntity26(level).also { cameraEntity = it }

    private fun withShake(pose: CameraPose, nanos: Long): CameraPose {
        val offset = rig.shake.offsetAt(nanos)
        if (offset === ShakeOffset.NONE) return pose
        return CameraPose(
            Vector3d(pose.position).add(offset.position),
            Rotation(pose.rotation.yaw + offset.rotation.yaw, pose.rotation.pitch + offset.rotation.pitch, pose.rotation.roll + offset.rotation.roll),
            pose.fov,
        )
    }

    private fun placeExport(pose: CameraPose, level: ClientLevel) {
        lastPose = pose
        pathActive = false
        val camera = cameraEntity(level)
        camera.place(pose)
        viewEntity = null
        switchCamera(camera)
    }

    private fun followPath(pose: CameraPose, level: ClientLevel) {
        lastPose = pose
        smoothedPose = null
        teleport(pose)
        val camera = cameraEntity(level)
        camera.place(pose)
        viewEntity = null
        switchCamera(camera)
        pathActive = true
    }

    fun onCameraUpdated(camera: Camera) {
        val accessor = camera as CameraAccessor
        val export = previewPose ?: exportPose
        if (export != null) {
            accessor.afterimage_setPosition(Vec3(export.position.x, export.position.y, export.position.z))
            accessor.afterimage_setRotation(export.rotation.yaw.toFloat(), export.rotation.pitch.toFloat())
            applyRoll(accessor, export.rotation.roll)
            return
        }
        val matrix = pendingMatrix
        if (matrix != null) {
            applyRecordedView(accessor, matrix)
            return
        }
        val roll = rollDegrees()
        if (roll != 0f) applyRoll(accessor, roll.toDouble())
    }

    private fun applyRoll(accessor: CameraAccessor, rollDegrees: Double) {
        if (rollDegrees == 0.0) return
        accessor.afterimage_rotation().rotateZ(-Math.toRadians(rollDegrees).toFloat())
        finishRotation(accessor)
    }

    private fun applyRecordedView(accessor: CameraAccessor, matrix: FloatArray) {
        val view = Matrix4f().set(matrix)
        val rotation = Matrix3f().set(view)
        val inverse = Matrix3f(rotation).invert()
        val translation = Vector3f(view.m30(), view.m31(), view.m32())
        val offset = inverse.transform(Vector3f(translation))
        val feet = lastPose.position
        val quaternion = inverse.getNormalizedRotation(Quaternionf())
        accessor.afterimage_setPosition(Vec3(feet.x - offset.x, feet.y - settings.eyeHeight - offset.y, feet.z - offset.z))
        accessor.afterimage_rotation().set(quaternion)
        finishRotation(accessor)
    }

    private fun finishRotation(accessor: CameraAccessor) {
        val rotation = accessor.afterimage_rotation()
        Vector3f(0f, 0f, -1f).rotate(rotation, accessor.afterimage_forwards())
        Vector3f(0f, 1f, 0f).rotate(rotation, accessor.afterimage_up())
        Vector3f(-1f, 0f, 0f).rotate(rotation, accessor.afterimage_left())
        accessor.afterimage_setMatrixPropertiesDirty(3)
    }

    fun handPose(viewRotation: Matrix4fc, original: Matrix4fc): Matrix4fc {
        val hand = pendingHand ?: return original
        if (exportPose != null || previewPose != null) return original
        handOverride.set(viewRotation).invert().mul(Matrix4f().set(hand))
        return handOverride
    }

    fun hideHand(): Boolean {
        if (exportPose != null || previewPose != null) return true
        if (!detachedFromPlayer()) return false
        if (settings.mode != CameraMode.FIRST_PERSON || !settings.showHand) return true
        if (settings.targetsRecorder()) return session?.shadow26?.localPlayer?.gameType == GameType.SPECTATOR
        return handTarget() == null
    }

    fun onSettleStart() {
        minecraft.player?.let { SwingState26.reset(it) }
    }

    fun onSeeked(replay: ReplaySession) {
        if (replay.settling || settings.mode != CameraMode.FIRST_PERSON || !settings.targetsRecorder()) return
        val player = minecraft.player ?: return
        val local = replay.shadow26.localPlayer
        SwingState26.apply(player, local.swings, local.effects.values, replay.lastTickNanos)
        val attackTicks = SwingState.ticksSince(local.lastAttackNanos, replay.lastTickNanos).coerceAtMost(MAX_ATTACK_TICKS)
        (player as LivingEntityAccessor).afterimage_setAttackStrengthTicker(attackTicks)
        (player as LivingEntityAccessor).afterimage_setItemSwapTicker(attackTicks)
    }

    private fun placeExactRecorderView(replay: ReplaySession): Boolean {
        val local = replay.shadow26.localPlayer
        val frame = local.cameraFrames.blended(replay.positionNanos, settings.interpolation != PoseInterpolation.SNAP) ?: return false
        val pose = local.poseAt(replay.positionNanos, interpolation())
        pendingMatrix = frame.modelView
        pendingHand = frame.hand
        pendingFov = if (settings.overrideFov) settings.fov.toFloat() else frame.fov
        val feet = frame.position
        val feetX = feet?.get(0) ?: pose.x
        val feetY = feet?.get(1) ?: pose.y
        val feetZ = feet?.get(2) ?: pose.z
        lastPose = CameraPose(Vector3d(feetX, feetY + settings.eyeHeight, feetZ), Rotation(pose.yaw.toDouble(), pose.pitch.toDouble()), pendingFov.toDouble())
        return placePlayerAsCamera(feetX, feetY, feetZ, pose.yaw, pose.pitch)
    }

    private fun placePlayerAsCamera(x: Double, y: Double, z: Double, yaw: Float, pitch: Float): Boolean {
        val player = minecraft.player ?: return false
        player.absSnapTo(x, y, z, yaw, pitch)
        player.setOldPosAndRot()
        player.yHeadRot = yaw
        player.yHeadRotO = yaw
        viewEntity = null
        switchCamera(player)
        return true
    }

    fun overridesCrosshair(): Boolean = session != null && exactRecorderView && !pathActive && exportPose == null

    fun overrideCrosshairTarget() {
        val replay = session ?: return
        if (!exactRecorderView || pathActive || exportPose != null) return
        val recorded = replay.shadow26.localPlayer.target ?: return
        val camera = minecraft.cameraEntity ?: return
        if (recorded.isBlock) {
            val pos = BlockPos(PackedPosition.x(recorded.position), PackedPosition.y(recorded.position), PackedPosition.z(recorded.position))
            val face = Direction.from3DDataValue(recorded.face)
            val center = Vec3(pos.x + 0.5 + face.stepX * 0.5, pos.y + 0.5 + face.stepY * 0.5, pos.z + 0.5 + face.stepZ * 0.5)
            minecraft.hitResult = BlockHitResult(center, face, pos, false)
        } else {
            val eye = camera.getEyePosition(tickDelta)
            minecraft.hitResult = BlockHitResult.miss(eye, Direction.UP, BlockPos.containing(eye))
        }
        minecraft.crosshairPickEntity = null
    }

    fun handTarget(): AbstractClientPlayer? {
        if (session == null || settings.mode != CameraMode.FIRST_PERSON || settings.targetsRecorder()) return null
        val entity = minecraft.level?.getEntity(settings.targetEntityId) ?: return null
        if (entity === minecraft.player) return null
        return entity as? AbstractClientPlayer
    }

    private fun mirrorViewRotation(pose: CameraPose) {
        val player = minecraft.player ?: return
        val yaw = pose.rotation.yaw.toFloat()
        val pitch = pose.rotation.pitch.toFloat()
        player.yRot = yaw
        player.yRotO = yaw
        player.xRot = pitch
        player.xRotO = pitch
        player.yHeadRot = yaw
        player.yHeadRotO = yaw
    }

    private fun mirrorHand(target: AbstractClientPlayer) {
        val player = minecraft.player ?: return
        val held = target.mainHandItem
        val current = player.mainHandItem
        if (!ItemStack.matches(held, current)) player.inventory.setItem(player.inventory.selectedSlot, held.copy())
        SwingState26.copy(target, player)
        if (target.isUsingItem) {
            if (!player.isUsingItem) player.startUsingItem(target.usedItemHand)
        } else if (player.isUsingItem) {
            player.stopUsingItem()
        }
    }

    fun onClientTick() {
        val replay = session ?: return
        handTarget()?.let { mirrorHand(it) }
        if (!(settings.mode == CameraMode.FIRST_PERSON && settings.targetsRecorder())) return
        val player = minecraft.player ?: return
        val recorder = minecraft.level?.getEntity(replay.shadow26.localPlayer.entityId) as? Player ?: return
        if (recorder.isUsingItem) {
            if (!player.isUsingItem) player.startUsingItem(recorder.usedItemHand)
        } else if (player.isUsingItem) {
            player.stopUsingItem()
        }
    }

    fun fovOverride(): Float {
        previewPose?.let { return it.fov.toFloat() }
        if (exportPose != null && exportFov > 0f) return exportFov
        val session = this.session
        if (session != null) fovProvider(session.positionNanos)?.let { return it.toFloat() }
        if (pendingMatrix != null) return pendingFov
        if (pathActive) return lastPose.fov.toFloat()
        if (!detachedFromPlayer()) return if (settings.overrideFov) settings.fov.toFloat() else 0f
        val fov = lastPose.fov
        return if (settings.overrideFov || fov != CameraPose.DEFAULT_FOV) fov.toFloat() else 0f
    }

    fun orthoScaleOverride(): Float? = if (exportPose != null) exportOrthoScale else null

    fun rollDegrees(): Float = when {
        previewPose != null -> previewPose!!.rotation.roll.toFloat()
        exportPose != null -> exportPose!!.rotation.roll.toFloat()
        pendingMatrix != null -> 0f
        pathActive || detachedFromPlayer() -> lastPose.rotation.roll.toFloat()
        else -> settings.roll.toFloat()
    }

    fun shouldHide(entity: Entity): Boolean = recorderPerspective == 0 && hiddenEntityId != Int.MIN_VALUE && entity.id == hiddenEntityId

    fun hurtCameraAllowed(): Boolean {
        if (exportPose != null || previewPose != null || pathActive) return false
        if (settings.mode != CameraMode.FIRST_PERSON) return false
        val camera = minecraft.cameraEntity ?: return false
        if (settings.targetsRecorder()) return camera === minecraft.player
        return camera === viewEntity
    }

    private fun targetSource(): PoseSource {
        if (settings.targetsRecorder()) return recorderPose
        val entityId = settings.targetEntityId
        return PoseSource { nanos ->
            val entity = minecraft.level?.getEntity(entityId)
            if (entity != null) return@PoseSource renderedPose(entity)
            val shadowEntity = session?.shadow26?.entityMap?.get(entityId) ?: return@PoseSource null
            val pose = shadowEntity.poseAt(nanos, interpolation())
            CameraPose(
                Vector3d(pose.x + settings.targetOffsetX, pose.y + bodyPartHeight(settings.eyeHeight) + settings.targetOffsetY, pose.z + settings.targetOffsetZ),
                Rotation(pose.headYaw.toDouble(), pose.pitch.toDouble(), settings.roll),
                effectiveFov(),
            )
        }
    }

    private fun bodyPartHeight(eyeHeight: Double): Double = when (settings.targetBodyPart) {
        TrackingBodyPart.HEAD -> eyeHeight
        TrackingBodyPart.BODY -> eyeHeight * 0.5
        TrackingBodyPart.ROOT -> 0.0
    }

    fun updateViewNumeric(view: ViewState) {
        when (val behavior = rig.behavior) {
            is OrbitCamera -> {
                behavior.distance = view.orbitDistance
                behavior.pitch = view.orbitPitch
                behavior.yawOffset = view.orbitYawOffset
                behavior.heightOffset = view.orbitHeight
                behavior.degreesPerSecond = view.orbitDegreesPerSecond
                behavior.fov = effectiveFov()
            }

            is FollowCamera -> {
                behavior.offset = Vector3d(view.followOffsetX, view.followOffsetY, view.followOffsetZ)
                behavior.lookAtTarget = view.followLookAtTarget
                behavior.fov = effectiveFov()
            }

            is ChaseCamera -> {
                behavior.distance = view.chaseDistance
                behavior.height = view.chaseHeight
                behavior.stiffness = view.chaseStiffness
                behavior.damping = view.chaseDamping
                behavior.fov = effectiveFov()
            }

            else -> {}
        }
    }

    private fun renderedPose(entity: Entity): CameraPose {
        val smoothed = entitySmoother.renderPosition(session, entity)
        val x = smoothed?.get(0) ?: (entity.xo + (entity.x - entity.xo) * tickDelta)
        val y = smoothed?.get(1) ?: (entity.yo + (entity.y - entity.yo) * tickDelta)
        val z = smoothed?.get(2) ?: (entity.zo + (entity.z - entity.zo) * tickDelta)
        val yaw = if (entity is LivingEntity) entity.yHeadRotO + (entity.yHeadRot - entity.yHeadRotO) * tickDelta else entity.yRotO + (entity.yRot - entity.yRotO) * tickDelta
        val pitch = entity.xRotO + (entity.xRot - entity.xRotO) * tickDelta
        return CameraPose(
            Vector3d(x + settings.targetOffsetX, y + bodyPartHeight(entity.eyeHeight.toDouble()) + settings.targetOffsetY, z + settings.targetOffsetZ),
            Rotation(yaw.toDouble(), pitch.toDouble(), settings.roll),
            effectiveFov(),
        )
    }

    private fun playerPose(): CameraPose? {
        val player = minecraft.player ?: return null
        val x = player.xo + (player.x - player.xo) * tickDelta
        val y = player.yo + (player.y - player.yo) * tickDelta
        val z = player.zo + (player.z - player.zo) * tickDelta
        val yaw = player.yRotO + (player.yRot - player.yRotO) * tickDelta
        val pitch = player.xRotO + (player.xRot - player.xRotO) * tickDelta
        return CameraPose(Vector3d(x, y + player.eyeHeight, z), Rotation(yaw.toDouble(), pitch.toDouble(), settings.roll), effectiveFov())
    }

    private fun smooth(pose: CameraPose, deltaNanos: Long): CameraPose {
        val amount = settings.smoothing
        if (amount <= 0.0 || settings.mode != CameraMode.FIRST_PERSON) {
            smoothedPose = null
            return pose
        }
        val previous = smoothedPose ?: return pose.also { smoothedPose = it }
        val seconds = deltaNanos / Nanos.PER_SECOND.toDouble()
        val rate = 30.0 * (1.0 - amount) + 0.5
        val blend = (1.0 - exp(-rate * seconds)).coerceIn(0.0, 1.0)
        val blended = previous.lerp(pose, blend)
        smoothedPose = blended
        return blended
    }

    private fun release() {
        cameraEntity = null
        viewEntity = null
        val player = minecraft.player ?: return
        switchCamera(player)
    }

    private fun interpolation(): Interpolation = when (settings.interpolation) {
        PoseInterpolation.SNAP -> SnapInterpolation
        PoseInterpolation.LINEAR -> LinearInterpolation
        PoseInterpolation.CATMULL_ROM -> CatmullRomInterpolation
    }

    private fun effectiveFov(): Double = if (settings.overrideFov) settings.fov else minecraft.options.fov().get().toDouble()

    private companion object {
        const val MAX_ATTACK_TICKS = 1 shl 20
    }
}
