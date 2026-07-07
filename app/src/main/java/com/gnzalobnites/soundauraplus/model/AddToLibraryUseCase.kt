/*
 * This file is part of SoundAura, which is released under the terms of the Apache
 * License 2.0. See license.md in the project's root directory to see the full license.
 */

package com.gnzalobnites.soundauraplus.model

import android.net.Uri
import com.gnzalobnites.soundauraplus.model.database.PlaylistDao
import com.gnzalobnites.soundauraplus.model.database.Track
import com.gnzalobnites.soundauraplus.model.database.TrackNamesValidator
import com.gnzalobnites.soundauraplus.model.database.newPlaylistNameValidator
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

/**
 * Contenedor de métodos que añade playlists a la biblioteca.
 *
 * PRINCIPIO: SOLO guarda URIs que YA TIENEN permisos persistentes.
 * Los permisos deben haber sido adquiridos durante la selección.
 *
 * Si algún URI no tiene permiso, se rechaza TODO el grupo.
 * No se guardan "parcialmente" los archivos.
 */
class AddToLibraryUseCase @Inject constructor(
    private val validator: UriPermissionValidator,
    private val dao: PlaylistDao
) {

    fun trackNamesValidator(
        scope: CoroutineScope,
        initialTrackNames: List<String>
    ) = TrackNamesValidator(dao, scope, initialTrackNames)

    sealed class Result {
        data object Success : Result()
        data class Failure(
            val invalidUris: List<Uri>,
            val totalUris: Int
        ) : Result()
    }

    /**
     * Intenta añadir múltiples playlists de una sola pista.
     */
    suspend fun addSingleTrackPlaylists(
        names: List<String>,
        uris: List<Uri>
    ): Result {
        assert(names.size == uris.size)

        val validation = validator.validate(uris)

        if (validation.hasInvalid) {
            return Result.Failure(
                invalidUris = validation.invalid,
                totalUris = uris.size
            )
        }

        dao.insertSingleTrackPlaylists(names, uris, validation.valid)
        return Result.Success
    }

    fun newPlaylistNameValidator(
        scope: CoroutineScope,
        initialName: String
    ) = newPlaylistNameValidator(dao, scope, initialName)

    /**
     * Intenta añadir una playlist con múltiples pistas.
     */
    suspend fun addPlaylist(
        name: String,
        shuffle: Boolean,
        tracks: List<Track>,
        trackUris: List<Uri> = tracks.map(Track::uri)
    ): Result {
        val validation = validator.validate(trackUris)

        if (validation.hasInvalid) {
            return Result.Failure(
                invalidUris = validation.invalid,
                totalUris = trackUris.size
            )
        }

        dao.insertPlaylist(name, shuffle, tracks, validation.valid)
        return Result.Success
    }
}