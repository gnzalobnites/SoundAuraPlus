/* This file is part of SoundAura, which is released under
 * the terms of the Apache License 2.0. See license.md in
 * the project's root directory to see the full license. */
package com.gnzalobnites.soundauraplus.model

import android.net.Uri
import com.gnzalobnites.soundauraplus.model.database.Playlist
import com.gnzalobnites.soundauraplus.model.database.PlaylistDao
import com.gnzalobnites.soundauraplus.model.database.Track
import com.gnzalobnites.soundauraplus.model.database.TrackNamesValidator
import com.gnzalobnites.soundauraplus.model.database.newPlaylistNameValidator
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

/** Un contenedor de métodos que añade playlists (de una o varias pistas)
 * a la biblioteca de playlists de la app. */
class AddToLibraryUseCase(
    private val permissionHandler: UriPermissionHandler,
    private val dao: PlaylistDao,
) {
    @Inject constructor(
        permissionHandler: AndroidUriPermissionHandler,
        dao: PlaylistDao
    ): this(permissionHandler as UriPermissionHandler, dao)

    fun trackNamesValidator(
        scope: CoroutineScope,
        initialTrackNames: List<String>
    ) = TrackNamesValidator(dao, scope, initialTrackNames)

    /** Los dos subtipos, [Success] y [Failure], representan los posibles
     * resultados para llamadas a [addSingleTrackPlaylists] o [addPlaylist]. */
    sealed class Result {
        /** La operación tuvo éxito. */
        data object Success: Result()

        /** La operación falló. Las propiedades [permissionsUsed] y
         * [permissionAllowance] pueden ayudar a explicar la razón del fallo. */
        data class Failure(
            val permissionsUsed: Int,
            val permissionAllowance: Int
        ): Result()
    }

    /**
     * Intenta añadir múltiples playlists de una sola pista. Cada valor en [names]
     * se usará como nombre para un nuevo [Playlist], mientras que el [Uri] con
     * el mismo índice en [uris] se usará como la única pista de ese [Playlist].
     *
     * @return El [Result] de la operación
     */
    suspend fun addSingleTrackPlaylists(
        names: List<String>,
        uris: List<Uri>,
    ): Result {
        assert(names.size == uris.size)
        val newUris = dao.filterNewUris(uris)
        
        // IMPORTANTE: Adquirir permisos persistentes para los nuevos URIs
        val succeeded = permissionHandler.acquirePermissionsFor(newUris)

        return if (succeeded) {
            dao.insertSingleTrackPlaylists(names, uris, newUris)
            Result.Success
        } else Result.Failure(
            permissionsUsed = permissionHandler.usedAllowance,
            permissionAllowance = permissionHandler.totalAllowance)
    }

    fun newPlaylistNameValidator(
        scope: CoroutineScope,
        initialName: String
    ) = newPlaylistNameValidator(dao, scope, initialName)

    /**
     * Intenta añadir una playlist con los valores [name] y [shuffle] dados y
     * con una lista de pistas igual a [tracks]. Si no hay suficientes permisos
     * de archivo para añadir todas las pistas de la nueva playlist, la operación
     * fallará y se devolverá el número de permisos extra que se necesitarían.
     *
     * @return El [Result] de la operación
     */
    suspend fun addPlaylist(
        name: String,
        shuffle: Boolean,
        tracks: List<Track>,
        trackUris: List<Uri> = tracks.map(Track::uri)
    ): Result {
        val newUris = dao.filterNewUris(trackUris)
        
        // IMPORTANTE: Adquirir permisos persistentes para los nuevos URIs
        val succeeded = permissionHandler.acquirePermissionsFor(newUris)

        return if (succeeded) {
            dao.insertPlaylist(name, shuffle, tracks, newUris)
            Result.Success
        } else Result.Failure(
            permissionsUsed = permissionHandler.usedAllowance,
            permissionAllowance = permissionHandler.totalAllowance)
    }
}