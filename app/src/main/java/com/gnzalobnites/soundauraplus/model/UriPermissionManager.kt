/*
 * This file is part of SoundAura, which is released under the terms of the Apache
 * License 2.0. See license.md in the project's root directory to see the full license.
 */

package com.gnzalobnites.soundauraplus.model

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.gnzalobnites.soundauraplus.logd
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gestiona los permisos persistentes para URIs de archivos.
 *
 * DECISIÓN DE DISEÑO:
 * - El Mutex protege TODA la operación de adquisición/liberación.
 * - Esto garantiza que no haya carreras en el límite de permisos.
 * - Las operaciones de Binder se ejecutan en Dispatchers.IO dentro del lock.
 * - Para SoundAura, donde las adquisiciones son poco frecuentes,
 *   esta es la solución más simple y correcta.
 */
@Singleton
class UriPermissionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val modeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION

    /**
     * Mutex para serializar TODAS las operaciones de adquisición/liberación.
     * Protege contra carreras en el límite de permisos.
     */
    private val permissionMutex = Mutex()

    /**
     * Índice en memoria de URIs con permisos persistentes.
     * @Volatile garantiza visibilidad entre hilos.
     */
    @Volatile
    private var persistedUris: Set<Uri> = emptySet()

    /**
     * Actualiza el índice de permisos desde el sistema.
     * Síncrona y NO suspend - solo lectura de ContentResolver.
     * SOLO debe llamarse dentro de [permissionMutex].
     */
    private fun updatePermissionIndex() {
        val uris = context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri }
            .toSet()
        persistedUris = uris
        logd("Índice de permisos actualizado: ${uris.size} URI(s)")
    }

    init {
        updatePermissionIndex()
    }

    /**
     * Refresca el índice de permisos desde el sistema.
     */
    suspend fun refreshPermissionIndex() = withContext(Dispatchers.IO) {
        permissionMutex.withLock {
            updatePermissionIndex()
            logd("Índice de permisos refrescado: ${persistedUris.size} URI(s)")
        }
    }

    /**
     * Verifica si un URI tiene permiso persistente.
     * O(1) y sin bloqueos.
     */
    fun hasPersistablePermission(uri: Uri): Boolean {
        return uri in persistedUris
    }

    /**
     * Filtra una lista de URIs, separando los válidos de los inválidos.
     */
    fun filterUris(uris: List<Uri>): PermissionFilterResult {
        val valid = mutableListOf<Uri>()
        val invalid = mutableListOf<Uri>()

        for (uri in uris) {
            if (uri in persistedUris) {
                valid.add(uri)
            } else {
                invalid.add(uri)
            }
        }

        return PermissionFilterResult(valid, invalid)
    }

    /**
     * Adquiere permisos persistentes para múltiples URIs.
     *
     * FLUJO:
     * 1. Toda la operación está protegida por Mutex.
     * 2. Operaciones de ContentResolver en Dispatchers.IO.
     * 3. Contador local para límite de permisos dentro del lote.
     */
    suspend fun acquirePersistablePermissions(uris: List<Uri>): PermissionResult {
        if (uris.isEmpty()) {
            return PermissionResult(emptyList(), emptyList())
        }

        return permissionMutex.withLock {
            withContext(Dispatchers.IO) {
                val max = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) 128 else 512
                var used = persistedUris.size

                val tentativeGranted = mutableListOf<Uri>()
                val denied = mutableListOf<Uri>()

                for (uri in uris) {
                    if (hasPersistablePermission(uri)) {
                        tentativeGranted.add(uri)
                        continue
                    }

                    if (used >= max) {
                        logd("Límite de permisos alcanzado: $used/$max")
                        denied.add(uri)
                        continue
                    }

                    try {
                        context.contentResolver.takePersistableUriPermission(uri, modeFlags)
                        tentativeGranted.add(uri)
                        used++
                        logd("Permiso intentado para: $uri")
                    } catch (e: SecurityException) {
                        logd("SecurityException al adquirir permiso para $uri: ${e.message}")
                        denied.add(uri)
                    }
                }

                updatePermissionIndex()

                val finalGranted = tentativeGranted.filter { hasPersistablePermission(it) }
                val finalDenied = (tentativeGranted - finalGranted.toSet()) + denied

                logd("Permisos adquiridos: ${finalGranted.size} concedidos, ${finalDenied.size} denegados")
                PermissionResult(finalGranted, finalDenied)
            }
        }
    }

    /**
     * Libera un permiso persistente para un URI.
     */
    suspend fun releasePersistablePermission(uri: Uri): Boolean {
        return permissionMutex.withLock {
            withContext(Dispatchers.IO) {
                if (!hasPersistablePermission(uri)) {
                    return@withContext true
                }

                try {
                    context.contentResolver.releasePersistableUriPermission(uri, modeFlags)
                    updatePermissionIndex()
                    logd("Permiso liberado: $uri")
                    true
                } catch (e: SecurityException) {
                    logd("Error al liberar permiso para $uri: ${e.message}")
                    false
                }
            }
        }
    }

    /**
     * Libera permisos persistentes para múltiples URIs.
     * El índice se refresca UNA SOLA VEZ al final.
     */
    suspend fun releasePersistablePermissions(uris: List<Uri>): Map<Uri, Boolean> {
        if (uris.isEmpty()) {
            return emptyMap()
        }

        return permissionMutex.withLock {
            withContext(Dispatchers.IO) {
                val results = mutableMapOf<Uri, Boolean>()

                for (uri in uris) {
                    if (!hasPersistablePermission(uri)) {
                        results[uri] = true
                        continue
                    }

                    try {
                        context.contentResolver.releasePersistableUriPermission(uri, modeFlags)
                        results[uri] = true
                        logd("Permiso liberado: $uri")
                    } catch (e: SecurityException) {
                        logd("Error al liberar permiso para $uri: ${e.message}")
                        results[uri] = false
                    }
                }

                updatePermissionIndex()
                results
            }
        }
    }

    /**
     * Obtiene el número total de permisos persistentes en uso.
     */
    fun getUsedPermissionCount(): Int {
        return persistedUris.size
    }

    /**
     * Obtiene el límite máximo de permisos persistentes.
     */
    fun getMaxPermissionCount(): Int {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) 128 else 512
    }

    data class PermissionResult(
        val granted: List<Uri>,
        val denied: List<Uri>
    ) {
        val allGranted get() = denied.isEmpty()
        val noneGranted get() = granted.isEmpty()
        val hasGranted get() = granted.isNotEmpty()
        val hasDenied get() = denied.isNotEmpty()
    }

    data class PermissionFilterResult(
        val valid: List<Uri>,
        val invalid: List<Uri>
    ) {
        val allValid get() = invalid.isEmpty()
        val noneValid get() = valid.isEmpty()
        val hasValid get() = valid.isNotEmpty()
        val hasInvalid get() = invalid.isNotEmpty()
    }
}