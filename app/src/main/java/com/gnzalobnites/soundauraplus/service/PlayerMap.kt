/*
 * This file is part of SoundAura, which is released under the terms of the Apache
 * License 2.0. See license.md in the project's root directory to see the full license.
 */

package com.gnzalobnites.soundauraplus.service

import android.content.Context
import android.net.Uri
import com.gnzalobnites.soundauraplus.model.UriPermissionManager

class PlayerMap(
    private val context: Context,
    private val permissionManager: UriPermissionManager,
    private val onPlaybackFailure: (uris: List<Uri>) -> Unit,
    private val onMissingPermissions: (uris: List<Uri>) -> Unit,
) {
    private var playerMap: MutableMap<Long, Player> = hashMapOf()

    val isEmpty get() = playerMap.isEmpty()

    fun play() = playerMap.values.forEach(Player::play)
    fun pause() = playerMap.values.forEach(Player::pause)
    fun stop() = playerMap.values.forEach(Player::stop)

    fun setPlayerVolume(playlistId: Long, volume: Float) =
        playerMap[playlistId]?.setVolume(volume)

    fun releaseAll() = playerMap.values.forEach(Player::release)

    fun update(playlists: Map<ActivePlaylistSummary, List<Uri>>, startPlaying: Boolean) {
        val oldMap = playerMap
        playerMap = HashMap(playlists.size)

        for (playlist in playlists) {
            val existingPlayer = oldMap
                .remove(playlist.id)
                ?.apply { update(playlist, startPlaying) }

            playerMap[playlist.id] = existingPlayer ?:
                Player(
                    context = context,
                    playlist = playlist,
                    startImmediately = startPlaying,
                    onPlaybackFailure = onPlaybackFailure,
                    onMissingPermissions = onMissingPermissions,
                    permissionManager = permissionManager
                )
        }
        oldMap.values.forEach(Player::release)
    }
}