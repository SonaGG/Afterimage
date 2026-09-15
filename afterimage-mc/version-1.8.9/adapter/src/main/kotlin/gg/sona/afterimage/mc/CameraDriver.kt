package gg.sona.afterimage.mc
import gg.sona.afterimage.mc.common.ExportCamera
import gg.sona.afterimage.mc.common.SceneCamera

import gg.sona.afterimage.camera.*
import gg.sona.afterimage.camera.impl.*
import gg.sona.afterimage.camera.shake.ShakeOffset
import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.editor.ViewState
import gg.sona.afterimage.editor.host.CameraControl
import gg.sona.afterimage.protocol.LocalScreen
import gg.sona.afterimage.mc.mixin.ItemInHandRendererAccessor
import gg.sona.afterimage.protocol47.perspective.ProjectionOptions
import gg.sona.afterimage.replay.session.ReplaySession
import gg.sona.afterimage.protocol47.shadow.HandState
import gg.sona.afterimage.world.interpolation.CatmullRomInterpolation
import gg.sona.afterimage.world.interpolation.Interpolation
import gg.sona.afterimage.world.interpolation.LinearInterpolation
import gg.sona.afterimage.world.interpolation.SnapInterpolation
import net.minecraft.client.Minecraft
import net.minecraft.client.entity.living.player.ClientPlayerEntity
import net.minecraft.client.options.KeyBinding
import net.minecraft.entity.Entity
import net.minecraft.entity.living.LivingEntity
import net.minecraft.entity.living.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.world.HitResult
import net.minecraft.world.World
import org.joml.Matrix4f
import org.joml.Vector3d
import org.joml.Vector3f
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.exp

class CameraDriver(private val minecraft: Minecraft, picker: ViewportPicker) : CameraControl, ExportCamera {
    override val settings = CameraSettings()
    val scene = SceneCamera(McSceneCameraHost(minecraft, picker) { settings.roll }, settings)
    val entitySmoother = EntitySmoother(minecraft, settings)

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
            settings.targetsRecorder() -> session?.shadow?.localPlayer?.entityId ?: Int.MIN_VALUE
            else -> settings.targetEntityId
        }

    var recorderPerspective: Int = 0
        private set

    var recordedHud: () -> LocalScreen? = { null }

    private var cameraEntity: ReplayCameraEntity? = null
    private var viewEntity: Entity? = null
    private var lastPose: CameraPose = CameraPose.ORIGIN
    private var smoothedPose: CameraPose? = null
    private var session: ReplaySession? = null
    private var tickDelta = 1f
    private var pendingMatrix: FloatArray? = null
    private var pendingHand: FloatArray? = null
    private var pendingFov = 0f
    private val matrixBuffer = BufferUtils.createFloatBuffer(16)
    private var previousHideGui = false
    private var previousPerspective = 0
    private var hideGuiManaged = false
    private var playerListForced = false

    private val recorderPose: PoseSource = PoseSource { nanos ->
        val replay = session ?: return@PoseSource null
        val pose = replay.shadow.localPlayer.poseAt(nanos, interpolation())
        CameraPose(
            Vector3d(pose.x, pose.y + settings.eyeHeight, pose.z),
            Rotation(pose.yaw.toDouble(), pose.pitch.toDouble(), settings.roll),
            effectiveFov()
        )
    }

    fun attach(replay: ReplaySession) {
        session = replay
        settings.mode = CameraMode.FREE
        settings.targetEntityId = CameraSettings.TARGET_RECORDER
        previousHideGui = minecraft.options.hideGui
        previousPerspective = minecraft.options.perspective
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
        if (hideGuiManaged) {
            minecraft.options.hideGui = previousHideGui
            minecraft.options.perspective = previousPerspective
            hideGuiManaged = false
        }
        if (playerListForced) {
            playerListForced = false
            KeyBinding.set(minecraft.options.playerListKey.keyCode, false)
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
            if (minecraft.camera !== minecraft.player && minecraft.player != null) teleport(lastPose)
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
            val pose =
                if (settings.mode == CameraMode.FIRST_PERSON && settings.targetsRecorder()) recorderPose.poseAt(time) else behavior.poseAt(
                    time
                )
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
        if (settings.mode == CameraMode.FIRST_PERSON && settings.targetsRecorder()) replay.shadow.localPlayer.screen.perspective else 0

    private fun thirdPersonPose(replay: ReplaySession, nanos: Long): CameraPose? {
        val local = replay.shadow.localPlayer
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
            Vector3d(
                (feet?.get(0) ?: pose.x) + offset.x,
                (feet?.get(1) ?: pose.y) + offset.y,
                (feet?.get(2) ?: pose.z) + offset.z
            ),
            Rotation(yaw, pitch, roll),
            if (settings.overrideFov) settings.fov else frame.fov.toDouble()
        )
    }

    override fun currentPose(): CameraPose =
        if (pathActive || detached || viewEntity != null) lastPose else playerPose() ?: lastPose

    override fun teleport(pose: CameraPose) {
        val player = minecraft.player ?: return
        player.setPositionAndAngles(
            pose.position.x,
            pose.position.y - player.getEyeHeight(),
            pose.position.z,
            pose.rotation.yaw.toFloat(),
            pose.rotation.pitch.toFloat()
        )
        player.prevX = player.x
        player.prevY = player.y
        player.prevZ = player.z
        player.lastYaw = player.yaw
        player.lastPitch = player.pitch
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

    fun onFrame(
        deltaNanos: Long,
        wallDeltaNanos: Long,
        tickDelta: Float,
        workspaceOpen: Boolean,
        viewportHovered: Boolean,
        keyboardFree: Boolean = true
    ) {
        recorderPerspective = 0
        placeCamera(deltaNanos, wallDeltaNanos, tickDelta, workspaceOpen, viewportHovered, keyboardFree)
        if (!hideGuiManaged) return
        val perspective = if (exportPose == null && previewPose == null) recorderPerspective else 0
        if (minecraft.options.perspective != perspective) minecraft.options.perspective = perspective
    }

    private fun placeCamera(
        deltaNanos: Long,
        wallDeltaNanos: Long,
        tickDelta: Float,
        workspaceOpen: Boolean,
        viewportHovered: Boolean,
        keyboardFree: Boolean
    ) {
        this.tickDelta = tickDelta
        val replay = session ?: return
        val world = minecraft.world ?: return
        pendingMatrix = null
        pendingHand = null
        if (hideGuiManaged) {
            val recorded = recordedHud()
            val hide = !(settings.mode == CameraMode.FIRST_PERSON && settings.showHud) || recorded?.hudHidden == true
            if (minecraft.options.hideGui != hide) minecraft.options.hideGui = hide
            val list = !hide && (settings.showPlayerList || recorded?.playerListShown == true)
            if (playerListForced != list) {
                playerListForced = list
                KeyBinding.set(minecraft.options.playerListKey.keyCode, list)
            }
        }
        exportPose?.let { pose ->
            if (pathProvider(replay.positionNanos) == null) recorderPerspective = recorderViewPerspective(replay)
            placeExport(pose, world)
            return
        }
        rig.shake.continuousAmplitude = shakeProvider(replay.positionNanos) ?: 0.0
        rig.shake.continuousFrequencyHz = shakeFrequencyProvider(replay.positionNanos) ?: settings.shakeFrequencyHz
        val pathPose = pathProvider(replay.positionNanos)
        if (pathPose != null && pathSuspended && (replay.playing || replay.positionNanos != suspendedAtNanos)) pathSuspended =
            false
        if (pathPose != null && !pathSuspended && settings.mode == CameraMode.FREE && workspaceOpen && !replay.playing && scene.wantsControl(
                viewportHovered,
                keyboardFree
            )
        ) {
            pathSuspended = true
            suspendedAtNanos = replay.positionNanos
        }
        if (pathPose != null && !pathSuspended) {
            followPath(withShake(pathPose, replay.positionNanos), world)
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
            recorderPerspective = replay.shadow.localPlayer.screen.perspective
            return
        }
        if (settings.mode == CameraMode.FREE) {
            if (workspaceOpen) scene.onFrame(wallDeltaNanos, viewportHovered, keyboardFree) else scene.cancelGesture()
            playerPose()?.let { lastPose = it }
            return
        }
        if (settings.mode == CameraMode.FIRST_PERSON && !settings.targetsRecorder()) {
            val entity = world.getEntity(settings.targetEntityId)
            if (entity != null) {
                if (minecraft.camera !== entity) minecraft.setCamera(entity)
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
            placePlayerAsCamera(
                pose.position.x,
                pose.position.y - player.getEyeHeight(),
                pose.position.z,
                pose.rotation.yaw.toFloat(),
                pose.rotation.pitch.toFloat()
            )
            recorderPerspective = replay.shadow.localPlayer.screen.perspective
            return
        }
        val camera = cameraEntity?.takeIf { it.world === world } ?: ReplayCameraEntity(world).also { cameraEntity = it }
        camera.place(pose)
        viewEntity = null
        if (minecraft.camera !== camera) minecraft.setCamera(camera)
    }

    private fun withShake(pose: CameraPose, nanos: Long): CameraPose {
        val offset = rig.shake.offsetAt(nanos)
        if (offset === ShakeOffset.NONE) return pose
        return CameraPose(
            Vector3d(pose.position).add(offset.position),
            Rotation(
                pose.rotation.yaw + offset.rotation.yaw,
                pose.rotation.pitch + offset.rotation.pitch,
                pose.rotation.roll + offset.rotation.roll
            ),
            pose.fov,
        )
    }

    private fun placeExport(pose: CameraPose, world: World) {
        lastPose = pose
        pathActive = false
        val camera = cameraEntity?.takeIf { it.world === world } ?: ReplayCameraEntity(world).also { cameraEntity = it }
        camera.place(pose)
        viewEntity = null
        if (minecraft.camera !== camera) minecraft.setCamera(camera)
    }

    private fun followPath(pose: CameraPose, world: World) {
        lastPose = pose
        smoothedPose = null
        teleport(pose)
        val camera = cameraEntity?.takeIf { it.world === world } ?: ReplayCameraEntity(world).also { cameraEntity = it }
        camera.place(pose)
        viewEntity = null
        if (minecraft.camera !== camera) minecraft.setCamera(camera)
        pathActive = true
    }

    fun onCameraSetup() {
        val export = previewPose ?: exportPose
        if (export != null) {
            val matrix = Matrix4f()
                .rotateZ(Math.toRadians(export.rotation.roll).toFloat())
                .rotateX(Math.toRadians(export.rotation.pitch).toFloat())
                .rotateY(Math.toRadians(export.rotation.yaw + 180.0).toFloat())
            loadMatrix(matrix.get(FloatArray(16)))
            return
        }
        loadMatrix(pendingMatrix ?: return)
    }

    fun onHandSetup() {
        loadMatrix(pendingHand ?: return)
    }

    private fun loadMatrix(matrix: FloatArray) {
        matrixBuffer.clear()
        matrixBuffer.put(matrix)
        matrixBuffer.flip()
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glLoadMatrixf(matrixBuffer)
    }

    fun hideHand(): Boolean {
        if (exportPose != null || previewPose != null) return true
        if (!detachedFromPlayer()) return false
        if (settings.mode != CameraMode.FIRST_PERSON || !settings.showHand) return true
        if (settings.targetsRecorder()) return session?.shadow?.localPlayer?.gameMode == ProjectionOptions.SPECTATOR
        return handTarget() == null
    }

    fun onSeeked(replay: ReplaySession) {
        if (settings.mode != CameraMode.FIRST_PERSON || !settings.targetsRecorder()) return
        val player = minecraft.player ?: return
        val state = HandState.at(replay.shadow.localPlayer, replay.positionNanos, replay.lastTickNanos)
        player.easedYaw = state.easedYaw
        player.easedPitch = state.easedPitch
        player.lastEasedYaw = state.lastEasedYaw
        player.lastEasedPitch = state.lastEasedPitch
        SeekRestorer.applySwing(player, state.swing)
        val renderer = minecraft.gameRenderer.itemInHandRenderer as ItemInHandRendererAccessor
        renderer.`afterimage$setHandHeight`(state.handHeight)
        renderer.`afterimage$setLastHandHeight`(state.lastHandHeight)
        val previous = state.previousItem
        renderer.`afterimage$setItemInHand`(if (previous != null) ItemStacks.toMinecraft(previous) else player.inventory.selectedItem)
        renderer.`afterimage$setSelectedSlot`(player.inventory.selectedSlot)
    }

    private fun placeExactRecorderView(replay: ReplaySession): Boolean {
        val local = replay.shadow.localPlayer
        val frame = local.cameraFrames.blended(replay.positionNanos, settings.interpolation != PoseInterpolation.SNAP)
            ?: return false
        val pose = local.poseAt(replay.positionNanos, interpolation())
        pendingMatrix = frame.modelView
        pendingHand = frame.hand
        pendingFov = if (settings.overrideFov) settings.fov.toFloat() else frame.fov
        val feet = frame.position
        val feetX = feet?.get(0) ?: pose.x
        val feetY = feet?.get(1) ?: pose.y
        val feetZ = feet?.get(2) ?: pose.z
        lastPose = CameraPose(
            Vector3d(feetX, feetY + settings.eyeHeight, feetZ),
            Rotation(pose.yaw.toDouble(), pose.pitch.toDouble()),
            pendingFov.toDouble()
        )
        return placePlayerAsCamera(feetX, feetY, feetZ, pose.yaw, pose.pitch)
    }

    private fun placePlayerAsCamera(x: Double, y: Double, z: Double, yaw: Float, pitch: Float): Boolean {
        val player = minecraft.player ?: return false
        player.setPositionAndAngles(x, y, z, yaw, pitch)
        player.lastYaw = yaw
        player.lastPitch = pitch
        player.headYaw = yaw
        player.lastHeadYaw = yaw
        viewEntity = null
        if (minecraft.camera !== player) minecraft.setCamera(player)
        return true
    }

    fun overrideCrosshairTarget() {
        val replay = session ?: return
        if (!exactRecorderView || pathActive || exportPose != null) return
        val recorded = replay.shadow.localPlayer.target ?: return
        val camera = minecraft.camera ?: return
        if (recorded.isBlock) {
            val pos = BlockPos.fromLong(recorded.position)
            val face = Direction.byId(recorded.face)
            val center = Vec3d(
                pos.x + 0.5 + face.offsetX * 0.5,
                pos.y + 0.5 + face.offsetY * 0.5,
                pos.z + 0.5 + face.offsetZ * 0.5
            )
            minecraft.crosshairTarget = HitResult(center, face, pos)
        } else {
            val eye = camera.getEyePosition(tickDelta)
            minecraft.crosshairTarget = HitResult(HitResult.Type.MISS, eye, null, BlockPos(eye))
        }
        minecraft.targetEntity = null
    }

    fun handTarget(): ClientPlayerEntity? {
        if (session == null || settings.mode != CameraMode.FIRST_PERSON || settings.targetsRecorder()) return null
        val entity = minecraft.world?.getEntity(settings.targetEntityId) ?: return null
        if (entity === minecraft.player) return null
        return entity as? ClientPlayerEntity
    }

    private fun mirrorViewRotation(pose: CameraPose) {
        val player = minecraft.player ?: return
        val yaw = pose.rotation.yaw.toFloat()
        val pitch = pose.rotation.pitch.toFloat()
        player.yaw = yaw
        player.lastYaw = yaw
        player.pitch = pitch
        player.lastPitch = pitch
        player.headYaw = yaw
        player.lastHeadYaw = yaw
    }

    private fun mirrorHand(target: ClientPlayerEntity) {
        val player = minecraft.player ?: return
        val held = target.itemInHand
        val current = player.inventory.selectedItem
        if (held == null && current != null || held != null && (current == null || !ItemStack.matches(held, current))) {
            player.inventory.setItem(player.inventory.selectedSlot, held?.copy())
        }
        player.armSwinging = target.armSwinging
        player.armSwingingTicks = target.armSwingingTicks
        player.attackAnimationProgress = target.attackAnimationProgress
        player.lastAttackAnimationProgress = target.lastAttackAnimationProgress
        if (target.hasItemInUse()) {
            val stack = player.inventory.selectedItem
            if (stack != null) player.setItemInUse(
                stack,
                target.itemUseTimer
            ) else if (player.hasItemInUse()) player.clearItemInUse()
        } else if (player.hasItemInUse()) {
            player.clearItemInUse()
        }
    }

    fun onClientTick() {
        val replay = session ?: return
        handTarget()?.let { mirrorHand(it) }
        if (!(settings.mode == CameraMode.FIRST_PERSON && settings.targetsRecorder())) return
        val player = minecraft.player ?: return
        val recorder = minecraft.world?.getEntity(replay.shadow.localPlayer.entityId) as? PlayerEntity ?: return
        if (recorder.hasItemInUse()) {
            val held = player.inventory.selectedItem
            if (held != null) player.setItemInUse(
                held,
                recorder.itemUseTimer
            ) else if (player.hasItemInUse()) player.clearItemInUse()
        } else if (player.hasItemInUse()) {
            player.clearItemInUse()
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

    fun rollDegrees(): Float =
        if (previewPose != null) previewPose!!.rotation.roll.toFloat() else if (exportPose != null) exportPose!!.rotation.roll.toFloat() else if (pendingMatrix != null) 0f else if (pathActive || detachedFromPlayer()) lastPose.rotation.roll.toFloat() else settings.roll.toFloat()

    fun shouldHide(entity: Entity): Boolean =
        recorderPerspective == 0 && hiddenEntityId != Int.MIN_VALUE && entity.networkId == hiddenEntityId

    fun hurtCameraAllowed(): Boolean {
        if (exportPose != null || previewPose != null || pathActive) return false
        if (settings.mode != CameraMode.FIRST_PERSON) return false
        val camera = minecraft.camera ?: return false
        if (settings.targetsRecorder()) return camera === minecraft.player
        return camera === viewEntity
    }

    private fun targetSource(): PoseSource {
        if (settings.targetsRecorder()) return recorderPose
        val entityId = settings.targetEntityId
        return PoseSource { nanos ->
            val entity = minecraft.world?.getEntity(entityId)
            if (entity != null) return@PoseSource renderedPose(entity)
            val shadowEntity = session?.shadow?.entities?.get(entityId) ?: return@PoseSource null
            val pose = shadowEntity.poseAt(nanos, interpolation())
            CameraPose(
                Vector3d(
                    pose.x + settings.targetOffsetX,
                    pose.y + bodyPartHeight(settings.eyeHeight) + settings.targetOffsetY,
                    pose.z + settings.targetOffsetZ
                ),
                Rotation(pose.headYaw.toDouble(), pose.pitch.toDouble(), settings.roll),
                effectiveFov()
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
        val x = smoothed?.get(0) ?: (entity.prevX + (entity.x - entity.prevX) * tickDelta)
        val y = smoothed?.get(1) ?: (entity.prevY + (entity.y - entity.prevY) * tickDelta)
        val z = smoothed?.get(2) ?: (entity.prevZ + (entity.z - entity.prevZ) * tickDelta)
        val yaw =
            if (entity is LivingEntity) entity.lastHeadYaw + (entity.headYaw - entity.lastHeadYaw) * tickDelta else entity.lastYaw + (entity.yaw - entity.lastYaw) * tickDelta
        val pitch = entity.lastPitch + (entity.pitch - entity.lastPitch) * tickDelta
        return CameraPose(
            Vector3d(
                x + settings.targetOffsetX,
                y + bodyPartHeight(entity.eyeHeight.toDouble()) + settings.targetOffsetY,
                z + settings.targetOffsetZ
            ),
            Rotation(yaw.toDouble(), pitch.toDouble(), settings.roll),
            effectiveFov()
        )
    }

    private fun playerPose(): CameraPose? {
        val player = minecraft.player ?: return null
        val x = player.prevX + (player.x - player.prevX) * tickDelta
        val y = player.prevY + (player.y - player.prevY) * tickDelta
        val z = player.prevZ + (player.z - player.prevZ) * tickDelta
        val yaw = player.lastYaw + (player.yaw - player.lastYaw) * tickDelta
        val pitch = player.lastPitch + (player.pitch - player.lastPitch) * tickDelta
        return CameraPose(
            Vector3d(x, y + player.getEyeHeight(), z),
            Rotation(yaw.toDouble(), pitch.toDouble(), settings.roll),
            effectiveFov()
        )
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
        if (minecraft.camera !== player) minecraft.setCamera(player)
    }

    private fun interpolation(): Interpolation = when (settings.interpolation) {
        PoseInterpolation.SNAP -> SnapInterpolation
        PoseInterpolation.LINEAR -> LinearInterpolation
        PoseInterpolation.CATMULL_ROM -> CatmullRomInterpolation
    }

    private fun effectiveFov(): Double = if (settings.overrideFov) settings.fov else minecraft.options.fov.toDouble()

}
