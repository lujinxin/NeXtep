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
    private var surface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var activeGeometry: SlotGeometry? = null
    private var pendingLaunch: Intent? = null
    private var freshSurfaceRequired = false
    private var surfaceGeneration = 1
    private var listener: Listener? = null

    init {
        view.textureView.surfaceTextureListener = this
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    fun displayId(): Int? = virtualDisplay?.display?.displayId

    fun geometry(): SlotGeometry? = activeGeometry

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
        pendingLaunch = null
        releaseDisplay("workspace hidden")
        freshSurfaceRequired = true
    }

    fun launch(intent: Intent): Result<Unit> {
        val displayId = virtualDisplay?.display?.displayId
        if (displayId == null) {
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
        if (!active) return
        val geometry = SlotGeometry.matchingDefaultDisplay(applicationContext)
        // Sidebar layout changes resize the TextureView, not the full-resolution virtual
        // display. Detaching its Surface here needlessly blanks a still-valid app buffer.
        if (virtualDisplay != null && geometry == activeGeometry) {
            texture.setDefaultBufferSize(geometry.width, geometry.height)
            return
        }
        activeGeometry = geometry
        val display = virtualDisplay ?: return
        val currentSurface = surface ?: return
        runCatching {
            display.setSurface(null)
            texture.setDefaultBufferSize(geometry.width, geometry.height)
            display.resize(geometry.width, geometry.height, geometry.densityDpi)
            display.setSurface(currentSurface)
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

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    private fun createDisplay(texture: SurfaceTexture, width: Int, height: Int) {
        if (virtualDisplay != null || width <= 0 || height <= 0) return
        val geometry = SlotGeometry.matchingDefaultDisplay(applicationContext)
        activeGeometry = geometry
        texture.setDefaultBufferSize(geometry.width, geometry.height)
        val createdSurface = Surface(texture)
        factory.create(index, geometry, createdSurface)
            .onSuccess { display ->
                surface = createdSurface
                virtualDisplay = display
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
