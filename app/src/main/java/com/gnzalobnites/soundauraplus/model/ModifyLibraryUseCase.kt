/* This file is part of SoundAura, which is released under
 * the terms of the Apache License 2.0. See license.md in
 * the project's root directory to see the full license. */
package com.gnzalobnites.soundauraplus.model

import android.net.Uri
import com.gnzalobnites.soundauraplus.dialog.ValidatedNamingState
import com.gnzalobnites.soundauraplus.model.database.Playlist
import com.gnzalobnites.soundauraplus.model.database.PlaylistDao
import com.gnzalobnites.soundauraplus.model.database.Track
import com.gnzalobnites.soundauraplus.model.database.playlistRenameValidator
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

/** Un contenedor de métodos que modifican la biblioteca de playlists de la app. */
class ModifyLibraryUseCase(
    private val permissionHandler: UriPermissionHandler,
    private val dao: PlaylistDao,
) {
    @Inject constructor(
        permissionHandler: AndroidUriPermissionHandler,
        dao: PlaylistDao
    ): this(permissionHandler as UriPermissionHandler, dao)

    suspend fun togglePlaylistIsActive(playlistId: Long) {
        dao.toggleIsActive(playlistId)
    }

    suspend fun setPlaylistVolume(playlistId: Long, newVolume: Float) {
        dao.setVolume(playlistId, newVolume)
    }

    /**
     * Devuelve un [ValidatedNamingState] que se puede usar para renombrar la
     * playlist cuyo nombre antiguo coincide con [oldName]. Se llamará a [onFinished]
     * cuando el renombrado termine con éxito o no, y se puede usar, por ejemplo,
     * para descartar un diálogo de renombrado.
     */
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
        })

    /** Los dos subtipos, [Success] y [NewTracksNotAdded], representan
     * los posibles resultados para llamadas a [setPlaylistShuffleAndTracks]. */
    sealed class Result {
        /** La operación tuvo éxito. */
        data object Success: Result()

        /** Se modificó el shuffle, y se eliminaron las pistas a eliminar,
         * pero no se añadieron las nuevas pistas. Las propiedades [permissionsUsed]
         * y [permissionAllowance] pueden ayudar a explicar la razón del fallo.
         * Los uris de las pistas que no se pudieron añadir se proporcionan en [unaddedUris].*/
        data class NewTracksNotAdded(
            val unaddedUris: List<Uri>,
            val permissionsUsed: Int,
            val permissionAllowance: Int
        ): Result()
    }

    /**
     * Actualiza el [Playlist] identificado por [playlistId] para que tenga un
     * estado de shuffle activado/desactivado coincidente con [shuffle], y una
     * lista de pistas coincidente con [tracks]. Mientras que el estado de shuffle
     * de la playlist siempre se establecerá, la operación de actualización de la
     * lista de pistas puede fallar si el [UriPermissionHandler] en uso indica
     * que no se pudieron obtener permisos para todas las nuevas pistas.
     */
    suspend fun setPlaylistShuffleAndTracks(
        playlistId: Long,
        shuffle: Boolean,
        tracks: List<Track>
    ): Result {
        val uris = tracks.map(Track::uri)
        val newUris = dao.filterNewUris(uris)
        val releasableUris = dao.getUniqueUrisNotIn(uris, playlistId)
        permissionHandler.releasePermissionsFor(releasableUris)

        // IMPORTANTE: Adquirir permisos persistentes para los nuevos URIs
        val acquiredPermissions = permissionHandler.acquirePermissionsFor(newUris)
        return if (acquiredPermissions) {
            dao.setPlaylistShuffleAndTracks(
                playlistId = playlistId,
                shuffle = shuffle,
                tracks = tracks,
                newUris = newUris,
                removableUris = releasableUris)
            Result.Success
        } else {
            dao.setPlaylistShuffleAndTracks(
                playlistId = playlistId,
                shuffle = shuffle,
                tracks = tracks.filter { it.uri !in newUris.toSet() },
                newUris = emptyList(),
                removableUris = releasableUris)
            Result.NewTracksNotAdded(
                unaddedUris = newUris,
                permissionsUsed = permissionHandler.usedAllowance,
                permissionAllowance = permissionHandler.totalAllowance)
        }
    }

    /** Establece la propiedad de boost de volumen del [Playlist] identificado
     * por [playlistId] a [volumeBoostDb]. Los valores de [volumeBoostDb] se
     * forzarán al rango soportado de [0, 30]. */
    suspend fun setPlaylistVolumeBoostDb(
        playlistId: Long,
        volumeBoostDb: Int
    ) {
        dao.setVolumeBoostDb(playlistId, volumeBoostDb.coerceIn(0, 30))
    }

    /** Elimina el [Playlist] identificado por [id]. */
    suspend fun removePlaylist(id: Long) {
        val unusedTracks = dao.deletePlaylist(id)
        permissionHandler.releasePermissionsFor(unusedTracks)
    }
}