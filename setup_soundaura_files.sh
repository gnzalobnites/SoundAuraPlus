#!/bin/bash

# Script para crear/actualizar los archivos de SoundAuraPlus
# Uso: ./setup_soundaura_files.sh

set -e  # Salir si hay error

# Colores para output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}=== Configurando archivos de SoundAuraPlus ===${NC}\n"

# Función para crear directorio si no existe
create_dir() {
    if [ ! -d "$1" ]; then
        mkdir -p "$1"
        echo -e "${YELLOW}Directorio creado: $1${NC}"
    fi
}

# Función para crear archivo con contenido
create_file() {
    local filepath="$1"
    local content="$2"
    
    # Crear directorio si no existe
    create_dir "$(dirname "$filepath")"
    
    # Crear o sobreescribir archivo
    echo "$content" > "$filepath"
    echo -e "${GREEN}Archivo creado/actualizado: $filepath${NC}"
}

# 1. UriPermissionHandler.kt
create_file "app/src/main/java/com/gnzalobnites/soundauraplus/model/UriPermissionHandler.kt" '/*
 * This file is part of SoundAura, which is released under the terms of the Apache
 * License 2.0. See license.md in the project'\''s root directory to see the full license.
 */
package com.gnzalobnites.soundauraplus.model

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.gnzalobnites.soundauraplus.logd
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** UriPermissionHandler describes the expected interface for a
 * manager of a limited number of file permissions, each of which
 * is described via a [Uri].
 * [acquirePermissionsFor] and [releasePermissionsFor]. */
interface UriPermissionHandler {
    /** The maximum number of file permissions permitted by the platform. */
    val totalAllowance: Int

    /** The number of file permissions already used. */
    val usedAllowance: Int

    /** The number of file permissions remaining. */
    val remainingAllowance get() = totalAllowance - usedAllowance

    /**
     * Acquire permissions for each file [Uri] in [uris].
     *
     * The implementation should first attempt to acquire persistent permissions.
     * If that fails or is not possible, it may fall back to checking for general
     * storage permissions.
     *
     * @return Whether all of the permissions were successfully acquired or the app
     * already has general storage permissions as a fallback.
     */
    fun acquirePermissionsFor(uris: List<Uri>): Boolean

    /** Release any persisted permissions for the [Uri]s in [uris]. */
    fun releasePermissionsFor(uris: List<Uri>)
}

/** A mock [UriPermissionHandler] whose methods simulate
 * a limited number of permission allowances. */
class TestPermissionHandler: UriPermissionHandler {
    private val grantedPermissions = mutableSetOf<Uri>()

    override val totalAllowance = 12
    override val usedAllowance get() = grantedPermissions.size

    override fun acquirePermissionsFor(uris: List<Uri>): Boolean {
        val newUris = uris.filterNot(grantedPermissions::contains)
        val hasEnoughSpace = remainingAllowance >= uris.size
        if (hasEnoughSpace)
            grantedPermissions.addAll(newUris)
        return hasEnoughSpace
    }
    override fun releasePermissionsFor(uris: List<Uri>) {
        grantedPermissions.removeAll(uris.toSet())
    }
}

/**
 * An implementation of [UriPermissionHandler] that takes into account if the
 * app has audio media access (i.e. it has the READ_MEDIA_AUDIO permission on
 * API >= 33, or the READ_EXTERNAL_STORAGE permission on API < 33), and if not
 * takes persistable [Uri] permissions granted by the Android system. If a
 * persistable [Uri] permission was not originally granted by the Android
 * system for any of the [Uri]s in the list passed to [acquirePermissionsFor],
 * the operation will fail.
 */
@Singleton
class AndroidUriPermissionHandler @Inject constructor(
    @ApplicationContext private val context: Context,
): UriPermissionHandler {
    private val modeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
    private val hasStoragePermission get() = context.checkSelfPermission(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

    override val totalAllowance =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) 128
        else                                               512

    override val usedAllowance get() =
        context.contentResolver.persistedUriPermissions.size

    override fun acquirePermissionsFor(uris: List<Uri>): Boolean {
        // --- SOLUCIÓN: Siempre intentar permisos persistentes si hay espacio ---
        // 1. Verificar si tenemos espacio en el límite de permisos persistentes
        if (remainingAllowance >= uris.size) {
            var successfulGrants = 0
            for (uri in uris) {
                try {
                    context.contentResolver.takePersistableUriPermission(uri, modeFlags)
                    successfulGrants++
                } catch (e: SecurityException) {
                    logd("Attempted to obtain a persistable permission for " +
                         "$uri when no persistable permission was granted.")
                    // Si falla para una URI, liberamos las que sí se concedieron
                    // para no dejar el sistema en un estado inconsistente.
                    if (successfulGrants > 0) {
                        releasePermissionsFor(uris.subList(0, successfulGrants))
                    }
                    // Salimos del bucle y procedemos a la lógica de respaldo
                    break
                }
            }

            // Si logramos obtener permisos persistentes para todos, ¡éxito!
            if (successfulGrants == uris.size) {
                return true
            }
        }

        // 2. Si no hay espacio (superamos el límite) o falló obtener permisos persistentes,
        // caemos en usar el permiso de almacenamiento general como respaldo.
        // Nota: Este es un respaldo, no la primera opción.
        if (hasStoragePermission) {
            logd("Falling back to general storage permission for ${uris.size} URIs.")
            return true
        }

        // 3. Si no tenemos permisos persistentes ni permisos generales, la operación falla.
        return false
    }

    override fun releasePermissionsFor(uris: List<Uri>) {
        for (uri in uris) try {
            context.contentResolver.releasePersistableUriPermission(uri, modeFlags)
        } catch (e: SecurityException) {
            logd("Attempted to release Uri permission for $uri " +
                 "when no permission was previously granted")
        }
    }
}'

# 2. PlaylistRecoveryService.kt (nuevo archivo)
create_file "app/src/main/java/com/gnzalobnites/soundauraplus/service/PlaylistRecoveryService.kt" 'package com.gnzalobnites.soundauraplus.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.gnzalobnites.soundauraplus.model.UriPermissionHandler
import com.gnzalobnites.soundauraplus.model.database.PlaylistDao
import com.gnzalobnites.soundauraplus.model.database.Track
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PlaylistRecoveryService : Service() {

    @Inject
    lateinit var playlistDao: PlaylistDao

    @Inject
    lateinit var permissionHandler: UriPermissionHandler

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Corremos la recuperación en segundo plano sin bloquear el hilo principal
        CoroutineScope(Dispatchers.IO).launch {
            recoverPlaylistPermissions()
        }
        // No queremos que el servicio se mantenga vivo si no es necesario.
        // Se detendrá a sí mismo cuando termine la corrutina.
        return START_NOT_STICKY
    }

    private suspend fun recoverPlaylistPermissions() {
        // 1. Obtener todas las pistas de la base de datos
        val allTracks: List<Track> = playlistDao.getAllTracks()

        // 2. Intentar adquirir permisos para todas las URIs
        val urisToRecover = allTracks.map { it.uri }

        if (urisToRecover.isNotEmpty()) {
            // 3. Intentar adquirir permisos. Esto no bloqueará la UI.
            permissionHandler.acquirePermissionsFor(urisToRecover)
        }

        // 4. Detener el servicio una vez finalizado
        stopSelf()
    }
}'

# 3. Application.kt (modificado)
create_file "app/src/main/java/com/gnzalobnites/soundauraplus/Application.kt" '/* This file is part of SoundAura, which is released under the terms of the Apache
 * License 2.0. See license.md in the project'\''s root directory to see the full license. */

package com.gnzalobnites.soundauraplus

import android.content.ComponentName
import android.content.Intent
import android.service.quicksettings.TileService
import android.util.Log
import com.gnzalobnites.soundauraplus.service.PlayerService
import com.gnzalobnites.soundauraplus.service.PlaylistRecoveryService
import com.gnzalobnites.soundauraplus.service.TogglePlaybackTileService
import com.gnzalobnites.soundauraplus.model.database.SoundAuraDatabase
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SoundAuraApplication : android.app.Application() {
    
    @Inject
    lateinit var database: SoundAuraDatabase

    override fun onCreate() {
        super.onCreate()

        PlayerService.addPlaybackChangeListener {
            if (!TogglePlaybackTileService.listening)
                TileService.requestListeningState(
                    this, ComponentName(this, TogglePlaybackTileService::class.java))
        }

        // --- NUEVO: Iniciar el servicio de recuperación al arrancar la app ---
        // Esto intentará adquirir permisos persistentes para todos los tracks existentes.
        val recoveryIntent = Intent(this, PlaylistRecoveryService::class.java)
        startService(recoveryIntent)
    }
}

fun logd(message: String) = Log.d("SoundAuraTag", message)'

# 4. PlaylistDao.kt (modificado)
create_file "app/src/main/java/com/gnzalobnites/soundauraplus/model/database/PlaylistDao.kt" '/* ... (resto de los imports y anotaciones superiores) ... */
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
     * Insert a single [Playlist] whose [Playlist.name] and [Playlist.shuffle]
     * vales will be equal to [playlistName] and [shuffle], respectively. The
     * [Uri]s in [tracks] will be added as the contents of the playlist.
     *
     * @return The [Long] id of the newly inserted [Playlist]
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
     * Attempt to add multiple single-track playlists. Each value in
     * [names] will be used as a name for a new [Playlist], while the
     * [Uri] with the same index in [uris] will be used as that [Playlist]'\''s
     * single track. The [Playlist.shuffle] value for the new [Playlist]s
     * will be the default value (i.e. false) due to shuffle having no
     * meaning for single-track playlists.
     *
     * If the [Uri]s in [uris] that are not already a part of any existing
     * [Playlist]s is already known, it can be passed in to the parameter
     * [newUris] to prevent the database inserting already existing tracks.
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

    /** Delete the playlist identified by [id] from the database. */
    @Query("DELETE FROM playlist WHERE id = :id")
    protected abstract suspend fun deletePlaylistName(id: Long)

    @Query("DELETE FROM playlistTrack WHERE playlistId = :playlistId")
    protected abstract suspend fun deletePlaylistTracks(playlistId: Long)

    @Query("DELETE FROM track WHERE uri IN (:uris)")
    protected abstract suspend fun deleteTracks(uris: List<Uri>)

    /** Delete the [Playlist] identified by [id] along with its contents.
     * @return the [List] of [Uri]s that are no longer a part of any playlist */
    @Transaction
    open suspend fun deletePlaylist(id: Long): List<Uri> {
        val removableTracks = getUniqueUris(id)
        deletePlaylistName(id)
        // playlistTrack.playlistName has an '\''on delete: cascade'\'' policy,
        // so the playlistTrack rows don'\''t need to be deleted manually
        deleteTracks(removableTracks)
        return removableTracks
    }

    @Query("SELECT shuffle FROM playlist WHERE id = :id LIMIT 1")
    abstract suspend fun getPlaylistShuffle(id: Long): Boolean

    @Query("UPDATE playlist SET shuffle = :shuffle WHERE id = :id")
    abstract suspend fun setPlaylistShuffle(id: Long, shuffle: Boolean)

    /**
     * Set the playlist identified by [playlistId] to have a [Playlist.shuffle]
     * value equal to [shuffle], and overwrite its tracks to be equal to [tracks].
     *
     * If the [Uri]s in [tracks] that are not already in any other playlists
     * has already been obtained, it can be passed as [newUris] to prevent the
     * database from needing to insert already existing tracks. Likewise, if
     * the [Uri]s that were previously a part of the playlist, but are not in
     * the new [tracks] and are not in any other playlist has already been
     * obtained, it can be passed as [removableUris] to prevent the database
     * from needing to recalculate the [Uri]s that are no longer needed.
     *
     * @return The [List] of [Uri]s that are no longer in any [Playlist] after the change.
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

    /** Return the track uris of the [Playlist] identified by
     * [playlistId] that are not in any other [Playlist]s. */
    @Query("SELECT trackUri FROM playlistTrack " +
           "GROUP BY trackUri HAVING COUNT(playlistId) = 1 " +
                             "AND playlistId = :playlistId")
    protected abstract suspend fun getUniqueUris(playlistId: Long): List<Uri>

    /** Return the track uris of the [Playlist] identified by [playlistId]
     * that are not in any other [Playlist]s and are not in [exceptions]. */
    @Query("SELECT trackUri FROM playlistTrack " +
           "WHERE trackUri NOT IN (:exceptions) " +
           "GROUP BY trackUri HAVING COUNT(playlistId) = 1 " +
                             "AND playlistId = :playlistId")
    abstract suspend fun getUniqueUrisNotIn(exceptions: List<Uri>, playlistId: Long): List<Uri>

    @RawQuery
    protected abstract suspend fun filterNewUris(query: SupportSQLiteQuery): List<Uri>

    suspend fun filterNewUris(tracks: List<Uri>): List<Uri> {
        // The following query requires parentheses around each argument. This
        // is not supported by Room, so the query must be made manually.
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

    /** Return whether or not a [Playlist] whose name matches [name] exists. */
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

    /** Return a [Flow] that updates with a [Map] of each active
     * [Playlist] (represented as an [ActivePlaylistSummary]
     * mapped to its tracks (represented as a [List] of [Uri]s). */
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

    /** Rename the [Playlist] identified by [id] to [newName]. */
    @Query("UPDATE playlist SET name = :newName WHERE id = :id")
    abstract suspend fun rename(id: Long, newName: String)

    /** Toggle the [Playlist.isActive] field of the [Playlist] identified by [id]. */
    @Query("UPDATE playlist set isActive = 1 - isActive WHERE id = :id")
    abstract suspend fun toggleIsActive(id: Long)

    /** Set the [Playlist.volume] field of the [Playlist] identified by [id]. */
    @Query("UPDATE playlist SET volume = :volume WHERE id = :id")
    abstract suspend fun setVolume(id: Long, volume: Float)

    /** Set the [Playlist.volumeBoostDb] field of the [Playlist identified by [id]. */
    @Query("UPDATE playlist SET volumeBoostDb = :dbBoost WHERE id = :id")
    abstract suspend fun setVolumeBoostDb(id: Long, dbBoost: Int)

    @Query("UPDATE track SET hasError = 1 WHERE uri in (:uris)")
    abstract suspend fun setTracksHaveError(uris: List<Uri>)

    // --- NUEVO: Método para obtener todos los tracks (para el servicio de recuperación) ---
    @Query("SELECT * FROM track")
    abstract suspend fun getAllTracks(): List<Track>

    // --- MÉTODO PARA EL WIDGET (ya lo tenías esbozado, ahora completo) ---
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

}'

echo -e "\n${GREEN}✓ Todos los archivos han sido creados/actualizados exitosamente${NC}"
echo -e "${YELLOW}Resumen de cambios:${NC}"
echo "  - Modificado: app/src/main/java/com/gnzalobnites/soundauraplus/model/UriPermissionHandler.kt"
echo "  - Nuevo: app/src/main/java/com/gnzalobnites/soundauraplus/service/PlaylistRecoveryService.kt"
echo "  - Modificado: app/src/main/java/com/gnzalobnites/soundauraplus/Application.kt"
echo "  - Modificado: app/src/main/java/com/gnzalobnites/soundauraplus/model/database/PlaylistDao.kt"

echo -e "\n${YELLOW}Próximos pasos:${NC}"
echo "  1. Revisa los archivos creados para asegurarte que todo está correcto"
echo "  2. Limpia y reconstruye el proyecto: ./gradlew clean build"
echo "  3. Ejecuta la app y verifica que los archivos existentes se recuperan correctamente"
