package com.narrator.jp

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 下拉即點，標記「直前」那條。手機在手但不想展開通知時用。 */
class FlagTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { t ->
            t.state = if (NarratorService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            t.label = getString(R.string.tile_label)
            t.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            if (Flagger.flagMostRecent(this@FlagTileService)) {
                Buzz.tick(this@FlagTileService)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
