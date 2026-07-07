/*
 * This file is part of SoundAura, which is released under the terms of the Apache
 * License 2.0. See license.md in the project's root directory to see the full license.
 */

package com.gnzalobnites.soundauraplus.model

import android.net.Uri
import com.gnzalobnites.soundauraplus.model.UriPermissionManager.PermissionFilterResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Validador de permisos de URI.
 *
 * PRINCIPIOS:
 * 1. SOLO valida - NO adquiere permisos
 * 2. Retorna el estado actual de los permisos
 * 3. No muestra mensajes al usuario (esa es responsabilidad de la UI)
 * 4. Es puro y fácil de probar
 */
@Singleton
class UriPermissionValidator @Inject constructor(
    private val permissionManager: UriPermissionManager
) {

    /**
     * Valida una lista de URIs, retornando los válidos e inválidos.
     * NO modifica el estado de los permisos.
     */
    fun validate(uris: List<Uri>): PermissionFilterResult {
        if (uris.isEmpty()) {
            return PermissionFilterResult(emptyList(), emptyList())
        }
        return permissionManager.filterUris(uris)
    }

    /**
     * Verifica si un URI tiene permiso persistente.
     * NO modifica el estado de los permisos.
     */
    fun isValid(uri: Uri): Boolean {
        return permissionManager.hasPersistablePermission(uri)
    }

    /**
     * Retorna solo los URIs válidos de la lista.
     * NO modifica el estado de los permisos.
     */
    fun getValidUris(uris: List<Uri>): List<Uri> {
        if (uris.isEmpty()) return emptyList()
        return permissionManager.filterUris(uris).valid
    }

    /**
     * Retorna los URIs inválidos de la lista.
     * NO modifica el estado de los permisos.
     */
    fun getInvalidUris(uris: List<Uri>): List<Uri> {
        if (uris.isEmpty()) return emptyList()
        return permissionManager.filterUris(uris).invalid
    }

    /**
     * Verifica si todos los URIs de la lista son válidos.
     * NO modifica el estado de los permisos.
     */
    fun allValid(uris: List<Uri>): Boolean {
        if (uris.isEmpty()) return true
        return permissionManager.filterUris(uris).allValid
    }

    /**
     * Verifica si ningún URI de la lista es válido.
     * NO modifica el estado de los permisos.
     */
    fun noneValid(uris: List<Uri>): Boolean {
        if (uris.isEmpty()) return true
        return permissionManager.filterUris(uris).noneValid
    }
}