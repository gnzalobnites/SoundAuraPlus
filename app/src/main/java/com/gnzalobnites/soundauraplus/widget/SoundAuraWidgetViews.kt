package com.gnzalobnites.soundauraplus.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.RemoteViews
import com.gnzalobnites.soundauraplus.R
import com.gnzalobnites.soundauraplus.service.PlayerService
import java.time.Instant
import java.time.Duration

object SoundAuraWidgetViews {

    fun updateWidgetUI(
        context: Context, 
        views: RemoteViews,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val isPlaying = PlayerService.playbackState == PlaybackStateCompat.STATE_PLAYING
        val isPaused = PlayerService.playbackState == PlaybackStateCompat.STATE_PAUSED

        val playPauseIcon = when {
            isPlaying -> R.drawable.ic_baseline_pause_24
            else -> R.drawable.ic_baseline_play_24
        }
        views.setImageViewResource(R.id.widget_play_pause, playPauseIcon)

        val statusText = when (PlayerService.playbackState) {
            PlaybackStateCompat.STATE_PLAYING -> context.getString(R.string.playing)
            PlaybackStateCompat.STATE_PAUSED -> context.getString(R.string.paused)
            else -> context.getString(R.string.stopped)
        }
        views.setTextViewText(R.id.widget_status, statusText)

        val stopTime = PlayerService.binder?.stopTime
        if (stopTime != null && isPlaying) {
            val remaining = Duration.between(Instant.now(), stopTime)
            if (!remaining.isNegative && !remaining.isZero) {
                val timeStr = formatDuration(remaining)
                views.setTextViewText(R.id.widget_timer, "⏱️ $timeStr")
                views.setViewVisibility(R.id.widget_timer, android.view.View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_timer, android.view.View.GONE)
            }
        } else {
            views.setViewVisibility(R.id.widget_timer, android.view.View.GONE)
        }

        val playlistName = if (isPlaying || isPaused) {
            context.getString(R.string.app_name)
        } else {
            context.getString(R.string.no_active_playlists)
        }
        views.setTextViewText(R.id.widget_playlist_name, playlistName)

        val showStopButton = PlayerService.playbackState != PlaybackStateCompat.STATE_STOPPED
        views.setViewVisibility(R.id.widget_stop,
            if (showStopButton) android.view.View.VISIBLE else android.view.View.GONE)

        val componentName = ComponentName(context, SoundAuraWidget::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_playlist_list)
    }

    private fun formatDuration(duration: Duration): String {
        val totalSeconds = duration.seconds
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            hours > 0 -> "%d:%02d:%02d".format(hours, minutes, seconds)
            minutes > 0 -> "%d:%02d".format(minutes, seconds)
            else -> "%ds".format(seconds)
        }
    }
}
