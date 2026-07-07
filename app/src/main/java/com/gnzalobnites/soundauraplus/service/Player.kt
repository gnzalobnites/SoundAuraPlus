/*
 * This file is part of SoundAura, which is released under the terms of the Apache
 * License 2.0. See license.md in the project's root directory to see the full license.
 */

package com.gnzalobnites.soundauraplus.service

import android.content.Context
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import com.gnzalobnites.soundauraplus.logd
import com.gnzalobnites.soundauraplus.model.UriPermissionManager
import java.io.IOException

data class ActivePlaylistSummary(
    val id: Long,
    val shuffle: Boolean,
    val volume: Float,
    val volumeBoostDb: Int
)

typealias ActivePlaylist = Map.Entry<ActivePlaylistSummary, List<Uri>>
val ActivePlaylist.id get() = key.id
val ActivePlaylist.shuffle get() = key.shuffle
val ActivePlaylist.volume get() = key.volume
val ActivePlaylist.volumeBoostDb get() = key.volumeBoostDb
val ActivePlaylist.tracks get() = value

class Player(
    private val context: Context,
    private var playlist: ActivePlaylist,
    startImmediately: Boolean = false,
    private val onPlaybackFailure: (List<Uri>) -> Unit,
    private val onMissingPermissions: (List<Uri>) -> Unit,
    private val permissionManager: UriPermissionManager
) {
    private var uriIterator = uriIterator(playlist)
    private var mediaPlayer: MediaPlayer? = null
    private var volumeBooster: LoudnessEnhancer? = null
    private var isPlaying = startImmediately
    private var hasPlayableTrack = false

    private val onCompletionListener = MediaPlayer.OnCompletionListener {
        initializePlayerForNextUri(startImmediately = isPlaying)
    }

    init {
        initializePlayerForNextUri(startImmediately)
        mediaPlayer?.initializeFor(playlist)
    }

    fun play() {
        if (!hasPlayableTrack) {
            logd("No se puede reproducir: no hay pistas reproducibles")
            return
        }
        isPlaying = true
        mediaPlayer?.start()
    }

    fun pause() {
        isPlaying = false
        mediaPlayer?.pause()
    }

    fun stop() {
        isPlaying = false
        if (playlist.tracks.size < 2) {
            mediaPlayer?.pause()
            mediaPlayer?.seekTo(0)
        } else {
            uriIterator = uriIterator(playlist)
            initializePlayerForNextUri(startImmediately = false)
        }
    }

    fun setVolume(volume: Float) {
        mediaPlayer?.setVolume(volume, volume)
    }

    fun update(newPlaylist: ActivePlaylist, startImmediately: Boolean) {
        isPlaying = startImmediately

        if (newPlaylist.shuffle != playlist.shuffle ||
            newPlaylist.tracks != playlist.tracks
        ) {
            uriIterator = uriIterator(newPlaylist)
            initializePlayerForNextUri(startImmediately)
            mediaPlayer?.initializeFor(newPlaylist)
        } else {
            setVolume(newPlaylist.volume)
            if (newPlaylist.volumeBoostDb != playlist.volumeBoostDb)
                mediaPlayer?.boostVolume(newPlaylist.volumeBoostDb)
            if (startImmediately && hasPlayableTrack)
                mediaPlayer?.start()
        }
        playlist = newPlaylist
    }

    fun release() {
        volumeBooster?.apply {
            enabled = false
            release()
        }
        volumeBooster = null

        mediaPlayer?.let {
            try {
                it.setOnCompletionListener(null)
                it.reset()
                it.release()
            } catch (e: Exception) {
                logd("Error al liberar MediaPlayer: ${e.message}")
            }
        }
        mediaPlayer = null
    }

    private fun uriIterator(playlist: ActivePlaylist) = (
        if (!playlist.shuffle)
            InfiniteSequence(playlist.tracks)
        else ShuffledInfiniteSequence(
            unshuffledValues = playlist.tracks,
            memorySize = maxOf(1, playlist.tracks.size / 3)
        )
        ).iterator()

    private fun initializePlayerForNextUri(startImmediately: Boolean) {
        var attempts = 0
        var newPlayer: MediaPlayer? = null
        var permissionMissingUris = mutableListOf<Uri>()
        var ioErrorUris = mutableListOf<Uri>()
        var foundPlayableTrack = false

        while (newPlayer == null && ++attempts <= playlist.tracks.size) {
            val uri = uriIterator.next()

            if (!permissionManager.hasPersistablePermission(uri)) {
                logd("URI sin permiso persistente: $uri")
                permissionMissingUris.add(uri)
                continue
            }

            try {
                if (mediaPlayer != null) {
                    try {
                        mediaPlayer?.reset()
                        mediaPlayer?.setDataSource(context, uri)
                        mediaPlayer?.prepare()
                        newPlayer = mediaPlayer
                    } catch (e: IOException) {
                        logd("IOException al preparar $uri: ${e.message}")
                        mediaPlayer?.release()
                        mediaPlayer = null
                        val created = MediaPlayer.create(context, uri)
                        if (created == null) {
                            logd("MediaPlayer.create devolvió null para: $uri")
                            ioErrorUris.add(uri)
                            continue
                        }
                        newPlayer = created
                    } catch (e: SecurityException) {
                        logd("SecurityException al acceder a $uri: ${e.message}")
                        permissionMissingUris.add(uri)
                        continue
                    }
                } else {
                    val created = MediaPlayer.create(context, uri)
                    if (created == null) {
                        logd("MediaPlayer.create devolvió null para: $uri")
                        ioErrorUris.add(uri)
                        continue
                    }
                    newPlayer = created
                }
            } catch (e: SecurityException) {
                logd("SecurityException en MediaPlayer.create($uri): ${e.message}")
                permissionMissingUris.add(uri)
                continue
            }

            if (newPlayer != null) {
                foundPlayableTrack = true
                if (startImmediately) {
                    newPlayer.start()
                }
            }
        }

        hasPlayableTrack = foundPlayableTrack

        if (ioErrorUris.isNotEmpty()) {
            logd("Error de IO en ${ioErrorUris.size} URI(s)")
            onPlaybackFailure(ioErrorUris)
        }

        if (permissionMissingUris.isNotEmpty()) {
            logd("URIs sin permiso persistente: ${permissionMissingUris.size}")
            onMissingPermissions(permissionMissingUris)
        }

        if (!hasPlayableTrack) {
            logd("No se encontraron pistas reproducibles")
            mediaPlayer?.release()
            mediaPlayer = null
        } else if (newPlayer != null) {
            mediaPlayer = newPlayer
        }
    }

    private fun MediaPlayer.initializeFor(playlist: ActivePlaylist) {
        setVolume(playlist.volume, playlist.volume)
        isLooping = playlist.tracks.size < 2
        boostVolume(playlist.volumeBoostDb)
        setOnCompletionListener(
            if (playlist.tracks.size < 2) null
            else onCompletionListener
        )
    }

    private fun MediaPlayer.boostVolume(dbBoost: Int) {
        volumeBooster?.apply {
            enabled = false
            release()
        }
        volumeBooster = if (dbBoost == 0) null else
            LoudnessEnhancer(audioSessionId).apply {
                setTargetGain(dbBoost * 100)
                enabled = true
            }
    }
}