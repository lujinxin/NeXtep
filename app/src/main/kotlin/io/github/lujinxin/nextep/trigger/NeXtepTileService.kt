package io.github.lujinxin.nextep.trigger

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import io.github.lujinxin.nextep.R

class NeXtepTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        TriggerCoordinator.queryState(this) { result ->
            updateFrom(result)
        }
    }

    override fun onClick() {
        super.onClick()
        TriggerCoordinator.requestToggle(this, TriggerSource.QUICK_SETTINGS_TILE) { result ->
            updateFrom(result)
            if (result is TriggerCoordinator.RequestResult.NotReady) {
                Toast.makeText(this, R.string.tile_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateFrom(result: TriggerCoordinator.RequestResult) {
        qsTile?.apply {
            label = getString(R.string.app_name)
            state = when (result) {
                is TriggerCoordinator.RequestResult.Ready -> {
                    if (result.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                }
                is TriggerCoordinator.RequestResult.NotReady -> Tile.STATE_UNAVAILABLE
            }
            updateTile()
        }
    }
}
