package io.github.lujinxin.nextep.display

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.hardware.display.VirtualDisplay
import android.view.Surface
import android.view.TextureView
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.framework.UserTargetedActivityLauncher
import io.github.lujinxin.nextep.workspace.WorkspaceLayoutProfile
import android.graphics.Matrix

class VirtualDisplaySlot(
    context: Context,
    private val index: Int,
) : TextureView.SurfaceTextureListener {
    interface Listener {
        fun onDisplayReady(slotIndex: Int, displayId: Int)
        fun onDisplayReleased(slotIndex: Int, displayId: Int?, reason: String)
        fun onDisplayFailed(slotIndex: Int, message: String)
    }

    val view = TaskSwitcherView(context, index)

    private val applicationContext = context.applicationContext ?: context
    private val factory = VirtualDisplayFactory(applicationContext)
    private var active = false
    private var suspended = false
    private var surface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputConnected = false
    private var activeGeometry: SlotGeometry? = null
    private var layoutProfile: WorkspaceLayoutProfile? = null
    private var pendingLaunch: Intent? = null
    private var freshSurfaceRequired = false
    private var surfaceGeneration = 1
    private var listener: Listener? = null

    internal var contentFrameSequence = 0L
        private set

    init {
        view.textureView.surfaceTextureListener = this
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    fun displayId(): Int? = virtualDisplay?.display?.displayId

    fun geometry(): SlotGeometry? = activeGeometry

    fun setLayoutProfile(profile: WorkspaceLayoutProfile) {
        if (layoutProfile == profile) return
        layoutProfile = profile
        val texture = view.textureView.surfaceTexture
        if (texture != null) onSurfaceTextureSizeChanged(texture, view.textureView.width, view.textureView.height)
    }

    fun sourceBounds(): WorkspaceLayoutProfile.Source = checkNotNull(layoutProfile).source

    fun isSuspended(): Boolean = suspended

    fun activate() {
        active = true
        val textureView = if (freshSurfaceRequired) {
            freshSurfaceRequired = false
            surfaceGeneration += 1
            view.replaceTextureView(this).also {
                NeXtepLog.info(
                    "virtual_display",
                    "Replaced TextureView slot=$index generation=$surfaceGeneration",
                )
            }
        } else {
            view.textureView
        }
        val texture = textureView.surfaceTexture
        if (textureView.isAvailable && texture != null) {
            createDisplay(texture, textureView.width, textureView.height)
        }
    }

    fun deactivate() {
        active = false
        suspended = false
        pendingLaunch = null
        releaseDisplay("workspace hidden")
        freshSurfaceRequired = true
    }

    fun setSuspended(value: Boolean) {
        if (value) {
            suspended = true
            // A null Surface turns the display off without removing its tasks/windows.
            // Keep our Surface alive so unlock can reconnect the same display.
            runCatching { updateOutputSurface(connected = false) }.onFailure {
                NeXtepLog.warn("virtual_display", "Unable to suspend output slot=$index", it)
            }
            return
        }
        if (!suspended) return
        if (!updateOutputSurface(connected = true)) return
        suspended = false
        val textureView = view.textureView
        val texture = textureView.surfaceTexture
        if (active && textureView.isAvailable && texture != null) {
            // Rotation/insets may have changed while output was disconnected.
            onSurfaceTextureSizeChanged(texture, textureView.width, textureView.height)
            createDisplay(texture, textureView.width, textureView.height)
        }
        virtualDisplay?.display?.displayId?.let { displayId ->
            pendingLaunch?.let { intent ->
                pendingLaunch = null
                launchNow(intent, displayId)
            }
        }
    }

    /** Warm buffers behind hidden panels without resuming input, task moves or pending launches. */
    fun updateSuspendedOutput(screenAwake: Boolean) {
        if (!active || !suspended) return
        updateOutputSurface(connected = screenAwake)
    }

    private fun updateOutputSurface(connected: Boolean): Boolean {
        val display = virtualDisplay ?: return true
        if (outputConnected == connected && (!connected || surface?.isValid == true)) return true
        if (!connected) {
            display.setSurface(null)
        } else {
            val currentSurface = surface?.takeIf { it.isValid } ?: view.textureView.surfaceTexture?.let {
                Surface(it).also { replacement -> surface?.release(); surface = replacement }
            } ?: return false
            check(currentSurface.isValid) { "Slot $index output Surface is invalid" }
            display.setSurface(currentSurface)
        }
        outputConnected = connected
        return true
    }

    fun launch(intent: Intent): Result<Unit> {
        val displayId = virtualDisplay?.display?.displayId
        if (suspended || displayId == null) {
            pendingLaunch = Intent(intent)
            view.showWaiting()
            return Result.success(Unit)
        }
        return launchNow(intent, displayId)
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (active) createDisplay(texture, width, height)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        if (!active || suspended) return
        if (width <= 0 || height <= 0) return
        val profile = checkNotNull(layoutProfile) { "Recorded layout must precede slot activation" }
        val geometry = profile.canvas
        // TextureView resets the producer buffer to its view size during layout.
        // Restore the recorded canvas even when the VirtualDisplay did not resize.
        texture.setDefaultBufferSize(geometry.width, geometry.height)
        val transform = profile.previewTransform(width, height)
        view.textureView.setTransform(Matrix().apply {
            setScale(transform.scaleX, transform.scaleY)
            postTranslate(transform.x, transform.y)
        })
        // Reuse the surface when dimensions settle or only the sidebar's position changes.
        if (virtualDisplay != null && geometry == activeGeometry) {
            return
        }
        activeGeometry = geometry
        val display = virtualDisplay ?: return
        if (surface == null) return
        runCatching {
            texture.setDefaultBufferSize(geometry.width, geometry.height)
            display.resize(geometry.width, geometry.height, geometry.densityDpi)
        }.onSuccess {
            NeXtepLog.info(
                "virtual_display",
                "Resized slot=$index generation=$surfaceGeneration geometry=$geometry",
            )
        }.onFailure { error ->
            NeXtepLog.error(
                "virtual_display",
                "Resize/rebind failed slot=$index generation=$surfaceGeneration",
                error,
            )
            releaseDisplay("resize/rebind failed")
            createDisplay(texture, width, height)
        }
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        releaseDisplay("surface destroyed")
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        contentFrameSequence += 1
    }

    private fun createDisplay(texture: SurfaceTexture, width: Int, height: Int) {
        if (!active || suspended || virtualDisplay != null || width <= 0 || height <= 0) return
        val geometry = checkNotNull(layoutProfile).canvas
        activeGeometry = geometry
        texture.setDefaultBufferSize(geometry.width, geometry.height)
        onSurfaceTextureSizeChanged(texture, width, height)
        val createdSurface = Surface(texture)
        factory.create(index, geometry, createdSurface)
            .onSuccess { display ->
                surface = createdSurface
                virtualDisplay = display
                outputConnected = true
                listener?.onDisplayReady(index, display.display.displayId)
                NeXtepLog.info(
                    "virtual_display",
                    "Created slot=$index generation=$surfaceGeneration " +
                        "displayId=${display.display.displayId} geometry=$geometry",
                )
                pendingLaunch?.let { intent ->
                    pendingLaunch = null
                    launchNow(intent, display.display.displayId)
                }
            }
            .onFailure {
                createdSurface.release()
                view.showWaiting()
                listener?.onDisplayFailed(index, it.message ?: "display unavailable")
            }
    }

    private fun launchNow(intent: Intent, displayId: Int): Result<Unit> = runCatching {
        val options = ActivityOptions.makeBasic().apply { setLaunchDisplayId(displayId) }
        UserTargetedActivityLauncher.start(
            applicationContext,
            Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            options.toBundle(),
        ).getOrThrow()
        NeXtepLog.info("virtual_display", "Launched slot=$index displayId=$displayId intent=$intent")
    }.onFailure { error ->
        view.showWaiting()
        NeXtepLog.error("virtual_display", "Launch failed slot=$index displayId=$displayId", error)
    }

    private fun releaseDisplay(reason: String) {
        val releasedDisplayId = virtualDisplay?.display?.displayId
        runCatching { virtualDisplay?.setSurface(null) }
        virtualDisplay?.release()
        virtualDisplay = null
        outputConnected = false
        activeGeometry = null
        surface?.release()
        surface = null
        view.showWaiting()
        NeXtepLog.info(
            "virtual_display",
            "Released slot=$index generation=$surfaceGeneration reason=$reason",
        )
        listener?.onDisplayReleased(index, releasedDisplayId, reason)
    }
}
