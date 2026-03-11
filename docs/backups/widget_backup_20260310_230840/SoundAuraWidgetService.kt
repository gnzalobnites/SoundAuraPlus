package com.cliffracertech.soundaura.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.RemoteViewsService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.cliffracertech.soundaura.service.PlayerService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SoundAuraWidgetService : LifecycleService() {

    @Inject
    lateinit var widgetUpdateHelper: SoundAuraWidgetUpdateHelper

    private var isRunning = false

    override fun onCreate() {
    super.onCreate()
    isRunning = true
    startPeriodicUpdates()
}

override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    super.onStartCommand(intent, flags, startId)
    return START_STICKY
}

    override fun onBind(intent: Intent): IBinder? {
    // RemoteViewsService maneja el binding automáticamente
    // Solo necesitamos devolver null o super.onBind()
    return super.onBind(intent)
}

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
    }

    private fun startPeriodicUpdates() {
        lifecycleScope.launch {
            while (isRunning) {
                if (PlayerService.playbackState == PlaybackStateCompat.STATE_PLAYING) {
                    requestWidgetUpdate()
                }
                delay(1000)
            }
        }
    }

    private fun requestWidgetUpdate() {
        val intent = Intent(this, SoundAuraWidget::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            val ids = AppWidgetManager.getInstance(this@SoundAuraWidgetService)
                .getAppWidgetIds(ComponentName(this@SoundAuraWidgetService, SoundAuraWidget::class.java))
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        sendBroadcast(intent)
    }
}

class SoundAuraWidgetUpdateHelper @Inject constructor() {
    // Helper para futuras mejoras
}
