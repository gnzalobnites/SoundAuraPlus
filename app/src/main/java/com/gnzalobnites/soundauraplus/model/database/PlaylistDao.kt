/* This file is part of SoundAura, which is released under
 * the terms of the Apache License 2.0. See license.md in
 * the project's root directory to see the full license. */
package com.gnzalobnites.soundauraplus.model.database

import android.net.Uri
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.MapInfo
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.gnzalobnites.soundauraplus.service.ActivePlaylistSummary
import kotlinx.coroutines.flow.Flow

typealias LibraryPlaylist = com.gnzalobnites.soundauraplus.library.Playlist

private const val librarySelectBase =
    "SELECT id, name, isActive, " +
           "COUNT(playlistId) = 1 AS isSingleTrack, " +
           "volume, volumeBoostDb, " +
           "SUM(track.hasError) = COUNT(track.hasError) as hasError " +
    "FROM playlist " +
    "JOIN playlistTrack ON playlist.id = playlistTrack.playlistId " +
    "JOIN track on playlistTrack.trackUri = track.uri "

private const val librarySelect =
    librarySelectBase + "GROUP BY playlistTrack.playlistId"

private const val librarySelectWithFilter =
    librarySelectBase +
    "WHERE name LIKE :filter " +
    "GROUP BY playlistTrack.playlistId"

@Dao abstract class PlaylistDao {
    @Query("SELECT last_insert_rowid()")
    protected abstract suspend fun getLastInsertId(): Long

    @Query("INSERT INTO track (uri) VALUES (:uri)")
    protected abstract suspend fun insertTrack(uri: Uri)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertTracks(tracks: List<Track>)

    @Query("INSERT INTO playlist (name, shuffle) VALUES (:name, :shuffle)")
    protected abstract suspend fun insertPlaylist(name: String, shuffle: Boolean = false)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertPlaylistTrack(playlistTrack: PlaylistTrack)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertPlaylistTracks(playlistTracks: List<PlaylistTrack>)

    /**
     * Insertar un único [Playlist] cuyos valores [Playlist.name] y [Playlist.shuffle]
     * serán iguales a [playlistName] y [shuffle], respectivamente. Los [Uri]s en
     * [tracks] se añadirán como contenido de la playlist.
     *
     * @return El [Long] id del [Playlist] recién insertado
     * */
    @Transaction
    open suspend fun insertPlaylist(
        playlistName: String,
        shuffle: Boolean,
        tracks: List<Track>,
        newUris: List<Uri>? = null,
    ): Long {
        insertTracks(newUris?.map(::Track) ?: tracks)
        insertPlaylist(playlistName, shuffle)
        val id = getLastInsertId()
        insertPlaylistTracks(tracks.mapIndexed { index, track ->
            PlaylistTrack(id, index, track.uri)
        })
        return id
    }

    /**
     * Intentar añadir múltiples playlists de una sola pista. Cada valor en
     * [names] se usará como nombre para un nuevo [Playlist], mientras que el
     * [Uri] con el mismo índice en [uris] se usará como la única pista de ese
     * [Playlist]. El valor [Playlist.shuffle] para los nuevos [Playlist]s
     * será el valor predeterminado (es decir, false) ya que shuffle no tiene
     * significado para playlists de una sola pista.
     *
     * Si los [Uri]s en [uris] que ya no son parte de ningún [Playlist] existente
     * ya se conocen, se pueden pasar en el parámetro [newUris] para evitar que
     * la base de datos inserte pistas ya existentes.
     */
    @Transaction
    open suspend fun insertSingleTrackPlaylists(
        names: List<String>,
        uris: List<Uri>,
        newUris: List<Uri>? = null,
    ) {
        assert(names.size == uris.size)
        insertTracks((newUris ?: uris).map(::Track))
        val playlistTracks = List(names.size) {
            insertPlaylist(names[it])
            PlaylistTrack(
                playlistId = getLastInsertId(),
                playlistOrder = 0,
                trackUri = uris[it])
        }
        insertPlaylistTracks(playlistTracks)
    }

    /** Eliminar la playlist identificada por [id] de la base de datos. */
    @Query("DELETE FROM playlist WHERE id = :id")
    protected abstract suspend fun deletePlaylistName(id: Long)

    @Query("DELETE FROM playlistTrack WHERE playlistId = :playlistId")
    protected abstract suspend fun deletePlaylistTracks(playlistId: Long)

    @Query("DELETE FROM track WHERE uri IN (:uris)")
    protected abstract suspend fun deleteTracks(uris: List<Uri>)

    /** Eliminar el [Playlist] identificado por [id] junto con su contenido.
     * @return la [List] de [Uri]s que ya no son parte de ninguna playlist */
    @Transaction
    open suspend fun deletePlaylist(id: Long): List<Uri> {
        val removableTracks = getUniqueUris(id)
        deletePlaylistName(id)
        // playlistTrack.playlistName tiene una política 'on delete: cascade',
        // por lo que las filas de playlistTrack no necesitan eliminarse manualmente
        deleteTracks(removableTracks)
        return removableTracks
    }

    @Query("SELECT shuffle FROM playlist WHERE id = :id LIMIT 1")
    abstract suspend fun getPlaylistShuffle(id: Long): Boolean

    @Query("UPDATE playlist SET shuffle = :shuffle WHERE id = :id")
    abstract suspend fun setPlaylistShuffle(id: Long, shuffle: Boolean)

    /**
     * Establecer la playlist identificada por [playlistId] para que tenga un valor
     * [Playlist.shuffle] igual a [shuffle], y sobrescribir sus pistas para que
     * sean iguales a [tracks].
     *
     * Si los [Uri]s en [tracks] que no están ya en otras playlists ya se han
     * obtenido, se pueden pasar como [newUris] para evitar que la base de datos
     * necesite insertar pistas ya existentes. Del mismo modo, si los [Uri]s que
     * antes formaban parte de la playlist, pero no están en las nuevas [tracks]
     * y no están en ninguna otra playlist ya se han obtenido, se pueden pasar
     * como [removableUris] para evitar que la base de datos recalcule los [Uri]s
     * que ya no son necesarios.
     *
     * @return La [List] de [Uri]s que ya no están en ninguna [Playlist] después del cambio.
     */
    @Transaction
    open suspend fun setPlaylistShuffleAndTracks(
        playlistId: Long,
        shuffle: Boolean,
        tracks: List<Track>,
        newUris: List<Uri>? = null,
        removableUris: List<Uri>? = null,
    ): List<Uri> {
        val removedUris = removableUris ?:
            getUniqueUrisNotIn(tracks.map(Track::uri), playlistId)
        deleteTracks(removedUris)
        insertTracks(newUris?.map(::Track) ?: tracks)

        deletePlaylistTracks(playlistId)
        insertPlaylistTracks(tracks.mapIndexed { index, track ->
            PlaylistTrack(playlistId, index, track.uri)
        })
        setPlaylistShuffle(playlistId, shuffle)
        return removedUris
    }

    /** Devuelve los uris de las pistas del [Playlist] identificado por
     * [playlistId] que no están en ningún otro [Playlist]. */
    @Query("SELECT trackUri FROM playlistTrack " +
           "GROUP BY trackUri HAVING COUNT(playlistId) = 1 " +
                             "AND playlistId = :playlistId")
    protected abstract suspend fun getUniqueUris(playlistId: Long): List<Uri>

    /** Devuelve los uris de las pistas del [Playlist] identificado por [playlistId]
     * que no están en ningún otro [Playlist] y no están en [exceptions]. */
    @Query("SELECT trackUri FROM playlistTrack " +
           "WHERE trackUri NOT IN (:exceptions) " +
           "GROUP BY trackUri HAVING COUNT(playlistId) = 1 " +
                             "AND playlistId = :playlistId")
    abstract suspend fun getUniqueUrisNotIn(exceptions: List<Uri>, playlistId: Long): List<Uri>

    @RawQuery
    protected abstract suspend fun filterNewUris(query: SupportSQLiteQuery): List<Uri>

    suspend fun filterNewUris(tracks: List<Uri>): List<Uri> {
        // La siguiente consulta requiere paréntesis alrededor de cada argumento.
        // Room no lo soporta, por lo que la consulta debe hacerse manualmente.
        val query = StringBuilder()
            .append("WITH newTrack(uri) AS (VALUES ")
            .apply {
                for (i in 0 until tracks.lastIndex)
                    append("(?), ")
            }.append("(?)) ")
            .append("SELECT newTrack.uri FROM newTrack ")
            .append("LEFT JOIN track ON track.uri = newTrack.uri ")
            .append("WHERE track.uri IS NULL;")
            .toString()
        val args = Array(tracks.size) { tracks[it].toString() }
        return filterNewUris(SimpleSQLiteQuery(query, args))
    }

    /** Devuelve si existe un [Playlist] cuyo nombre coincida con [name]. */
    @Query("SELECT EXISTS(SELECT name FROM playlist WHERE name = :name)")
    abstract suspend fun exists(name: String?): Boolean

    @Query("$librarySelect ORDER BY name COLLATE NOCASE ASC")
    abstract fun getPlaylistsSortedByNameAsc(): Flow<List<LibraryPlaylist>>

    @Query("$librarySelect ORDER BY name COLLATE NOCASE DESC")
    abstract fun getPlaylistsSortedByNameDesc(): Flow<List<LibraryPlaylist>>

    @Query("$librarySelect ORDER BY id ASC")
    abstract fun getPlaylistsSortedByOrderAdded(): Flow<List<LibraryPlaylist>>

    @Query("$librarySelect ORDER BY isActive DESC, name COLLATE NOCASE ASC")
    abstract fun getPlaylistsSortedByActiveThenNameAsc(): Flow<List<LibraryPlaylist>>

    @Query("$librarySelect ORDER BY isActive DESC, name COLLATE NOCASE DESC")
    abstract fun getPlaylistsSortedByActiveThenNameDesc(): Flow<List<LibraryPlaylist>>

    @Query("$librarySelect ORDER BY isActive DESC, id ASC")
    abstract fun getPlaylistsSortedByActiveThenOrderAdded(): Flow<List<LibraryPlaylist>>

    @Query("$librarySelectWithFilter ORDER BY name COLLATE NOCASE ASC")
    abstract fun getPlaylistsSortedByNameAsc(filter: String): Flow<List<LibraryPlaylist>>

    @Query("$librarySelectWithFilter ORDER BY name COLLATE NOCASE DESC")
    abstract fun getPlaylistsSortedByNameDesc(filter: String): Flow<List<LibraryPlaylist>>

    @Query("$librarySelectWithFilter ORDER BY id ASC")
    abstract fun getPlaylistsSortedByOrderAdded(filter: String): Flow<List<LibraryPlaylist>>

    @Query("$librarySelectWithFilter ORDER BY isActive DESC, name COLLATE NOCASE ASC")
    abstract fun getPlaylistsSortedByActiveThenNameAsc(filter: String): Flow<List<LibraryPlaylist>>

    @Query("$librarySelectWithFilter ORDER BY isActive DESC, name COLLATE NOCASE DESC")
    abstract fun getPlaylistsSortedByActiveThenNameDesc(filter: String): Flow<List<LibraryPlaylist>>

    @Query("$librarySelectWithFilter ORDER BY isActive DESC, id ASC")
    abstract fun getPlaylistsSortedByActiveThenOrderAdded(filter: String): Flow<List<LibraryPlaylist>>

    @Query("SELECT NOT EXISTS(SELECT 1 FROM playlist WHERE isActive)")
    abstract fun getNoPlaylistsAreActive(): Flow<Boolean>

    /** Devuelve un [Flow] que se actualiza con un [Map] de cada [Playlist] activo
     * (representado como un [ActivePlaylistSummary] mapeado a sus pistas
     * (representadas como una [List] de [Uri]s). */
    @MapInfo(valueColumn = "trackUri")
    @Query("SELECT id, shuffle, volume, volumeBoostDb, trackUri " +
           "FROM playlist " +
           "JOIN playlistTrack ON playlist.id = playlistTrack.playlistId " +
           "WHERE isActive ORDER by playlistOrder")
    abstract fun getActivePlaylistsAndTracks(): Flow<Map<ActivePlaylistSummary, List<Uri>>>

    @Query("SELECT name FROM playlist")
    abstract suspend fun getPlaylistNames(): List<String>

    @Query("SELECT uri, hasError FROM playlistTrack " +
           "JOIN track on playlistTrack.trackUri = track.uri " +
           "WHERE playlistId = :id ORDER by playlistOrder")
    abstract suspend fun getPlaylistTracks(id: Long): List<Track>

    /** Renombrar el [Playlist] identificado por [id] a [newName]. */
    @Query("UPDATE playlist SET name = :newName WHERE id = :id")
    abstract suspend fun rename(id: Long, newName: String)

    /** Alternar el campo [Playlist.isActive] del [Playlist] identificado por [id]. */
    @Query("UPDATE playlist set isActive = 1 - isActive WHERE id = :id")
    abstract suspend fun toggleIsActive(id: Long)

    /** Establecer el campo [Playlist.volume] del [Playlist] identificado por [id]. */
    @Query("UPDATE playlist SET volume = :volume WHERE id = :id")
    abstract suspend fun setVolume(id: Long, volume: Float)

    /** Establecer el campo [Playlist.volumeBoostDb] del [Playlist] identificado por [id]. */
    @Query("UPDATE playlist SET volumeBoostDb = :dbBoost WHERE id = :id")
    abstract suspend fun setVolumeBoostDb(id: Long, dbBoost: Int)

    @Query("UPDATE track SET hasError = 1 WHERE uri in (:uris)")
    abstract suspend fun setTracksHaveError(uris: List<Uri>)

    // --- MÉTODO PARA EL WIDGET ---
    @Query("""
        SELECT id, name, shuffle, isActive,
        COUNT(playlistId) = 1 AS isSingleTrack,
        volume, volumeBoostDb,
        SUM(track.hasError) = COUNT(track.hasError) as hasError
        FROM playlist
        JOIN playlistTrack ON playlist.id = playlistTrack.playlistId
        JOIN track on playlistTrack.trackUri = track.uri
        GROUP BY playlistTrack.playlistId
        ORDER BY isActive DESC, name COLLATE NOCASE ASC
        """)
    abstract suspend fun getPlaylistsForWidget(): List<LibraryPlaylist>
}