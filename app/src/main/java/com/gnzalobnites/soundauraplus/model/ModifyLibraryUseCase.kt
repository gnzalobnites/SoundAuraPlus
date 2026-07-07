/*
 * This file is part of SoundAura, which is released under the terms of the Apache
 * License 2.0. See license.md in the project's root directory to see the full license.
 */

package com.gnzalobnites.soundauraplus.model

import android.net.Uri
import com.gnzalobnites.soundauraplus.dialog.ValidatedNamingState
import com.gnzalobnites.soundauraplus.model.database.PlaylistDao
import com.gnzalobnites.soundauraplus.model.database.Track
import com.gnzalobnites.soundauraplus.model.database.playlistRenameValidator
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

class ModifyLibraryUseCase @Inject constructor(
    private val validator: UriPermissionValidator,
    private val permissionManager: UriPermissionManager,
    private val dao: PlaylistDao
) {

    suspend fun togglePlaylistIsActive(playlistId: Long) {
        dao.toggleIsActive(playlistId)
    }

    suspend fun setPlaylistVolume(playlistId: Long, newVolume: Float) {
        dao.setVolume(playlistId, newVolume)
    }

    fun renameState(
        playlistId: Long,
        oldName: String,
        scope: CoroutineScope,
        onFinished: () -> Unit
    ) = ValidatedNamingState(
        validator = playlistRenameValidator(dao, oldName, scope),
        coroutineScope = scope,
        onNameValidated = { newName ->
            if (newName != oldName)
                dao.rename(playlistId, newName)
            onFinished()
        }
    )

    sealed class Result {
        data object Success : Result()
        data class NewTracksNotAdded(
            val invalidUris: List<Uri>,
            val totalUris: Int
        ) : Result()
    }

    suspend fun setPlaylistShuffleAndTracks(
        playlistId: Long,
        shuffle: Boolean,
        tracks: List<Track>
    ): Result {
        val uris = tracks.map(Track::uri)

        val validation = validator.validate(uris)

        if (validation.hasInvalid) {
            return Result.NewTracksNotAdded(
                invalidUris = validation.invalid,
                totalUris = uris.size
            )
        }

        val currentUris = dao.getPlaylistTracks(playlistId).map(Track::uri)
        val uniqueUris = dao.getUniqueUrisNotIn(uris, playlistId)

        for (uri in uniqueUris) {
            if (permissionManager.hasPersistablePermission(uri)) {
                permissionManager.releasePersistablePermission(uri)
            }
        }

        dao.setPlaylistShuffleAndTracks(
            playlistId = playlistId,
            shuffle = shuffle,
            tracks = tracks,
            newUris = validation.valid,
            removableUris = uniqueUris
        )

        return Result.Success
    }

    suspend fun setPlaylistVolumeBoostDb(
        playlistId: Long,
        volumeBoostDb: Int
    ) {
        dao.setVolumeBoostDb(playlistId, volumeBoostDb.coerceIn(0, 30))
    }

    suspend fun removePlaylist(id: Long) {
        val unusedTracks = dao.deletePlaylist(id)
        for (uri in unusedTracks) {
            if (permissionManager.hasPersistablePermission(uri)) {
                permissionManager.releasePersistablePermission(uri)
            }
        }
    }
}