package com.cliffracertech.soundaura.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cliffracertech.soundaura.SoundAuraApplication
import com.cliffracertech.soundaura.service.PlayerService
import kotlinx.coroutines.*

class SoundAuraWidgetReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        
        when (action) {
            SoundAuraWidget.ACTION_PLAY_PAUSE,
            SoundAuraWidget.ACTION_STOP -> {
                SoundAuraWidget.sendAction(context, action)
            }
            SoundAuraWidget.ACTION_TOGGLE_PLAYLIST -> {
                val playlistId = intent.getLongExtra(SoundAuraWidget.EXTRA_PLAYLIST_ID, -1)
                if (playlistId != -1L) {
                    scope.launch {
                        // Obtener el PlaylistDao desde la aplicación
                        val application = context.applicationContext as SoundAuraApplication
                        val playlistDao = application.database.playlistDao()
                        
                        // Togglear el estado de la playlist
                        playlistDao.toggleIsActive(playlistId)
                        
                        withContext(Dispatchers.Main) {
                            SoundAuraWidget.sendAction(context, SoundAuraWidget.ACTION_UPDATE_WIDGET)
                        }
                    }
                }
            }
            SoundAuraWidget.ACTION_UPDATE_WIDGET -> {
                SoundAuraWidget.sendAction(context, SoundAuraWidget.ACTION_UPDATE_WIDGET)
            }
        }
    }
}
