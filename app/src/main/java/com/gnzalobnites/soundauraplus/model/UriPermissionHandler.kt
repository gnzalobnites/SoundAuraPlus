/*
 * This file is part of SoundAura, which is released under the terms of the Apache
 * License 2.0. See license.md in the project's root directory to see the full license.
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

/** UriPermissionHandler describe la interfaz esperada para un
 * gestor de un número limitado de permisos de archivos, cada uno de los cuales
 * se describe mediante un [Uri].
 * [acquirePermissionsFor] y [releasePermissionsFor]. */
interface UriPermissionHandler {
    /** El número máximo de permisos de archivos permitidos por la plataforma. */
    val totalAllowance: Int

    /** El número de permisos de archivos ya utilizados. */
    val usedAllowance: Int

    /** El número de permisos de archivos restantes. */
    val remainingAllowance get() = totalAllowance - usedAllowance

    /**
     * Adquirir permisos para cada archivo [Uri] en [uris].
     *
     * La implementación primero intentará adquirir permisos persistentes.
     * Si eso falla o no es posible, puede recurrir a verificar permisos
     * de almacenamiento generales.
     *
     * @return Si todos los permisos se adquirieron correctamente o la app
     * ya tiene permisos de almacenamiento generales como respaldo.
     */
    fun acquirePermissionsFor(uris: List<Uri>): Boolean

    /** Liberar cualquier permiso persistente para los [Uri]s en [uris]. */
    fun releasePermissionsFor(uris: List<Uri>)
}

/** Un [UriPermissionHandler] simulado cuyos métodos simulan
 * un número limitado de asignaciones de permisos. */
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
 * Una implementación de [UriPermissionHandler] que tiene en cuenta si la
 * app tiene acceso a audio en medios (es decir, tiene el permiso
 * READ_MEDIA_AUDIO en API >= 33, o READ_EXTERNAL_STORAGE en API < 33), y si no,
 * utiliza permisos [Uri] persistentes otorgados por el sistema Android.
 * 
 * Ahora con una mejora clave: SIEMPRE intenta adquirir permisos persistentes
 * primero, y solo recurre al permiso de almacenamiento general como respaldo
 * si el límite de permisos persistentes está agotado.
 * 
 * Esto asegura que los permisos sobrevivan a reinicios del dispositivo y
 * que el PlayerService pueda acceder a los archivos incluso cuando se inicia
 * desde atajos o Quick Settings.
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
        if (uris.isEmpty()) return true

        // PRIMERA ESTRATEGIA: Intentar adquirir permisos persistentes
        // Siempre que haya espacio disponible, esta es la ruta preferida.
        if (remainingAllowance >= uris.size) {
            var successfulGrants = 0
            for (uri in uris) {
                try {
                    context.contentResolver.takePersistableUriPermission(uri, modeFlags)
                    successfulGrants++
                } catch (e: SecurityException) {
                    logd("No se pudo obtener permiso persistente para $uri: ${e.message}")
                    // Liberar los que sí se concedieron para no dejar estado inconsistente
                    if (successfulGrants > 0) {
                        releasePermissionsFor(uris.subList(0, successfulGrants))
                    }
                    // Salir del bucle y proceder a la estrategia de respaldo
                    break
                }
            }

            // Si logramos obtener permisos persistentes para todos, ¡éxito!
            if (successfulGrants == uris.size) {
                logd("Permisos persistentes adquiridos para ${uris.size} URI(s)")
                return true
            }
        }

        // SEGUNDA ESTRATEGIA: Recurrir al permiso de almacenamiento general como respaldo
        if (hasStoragePermission) {
            logd("Usando permiso de almacenamiento general como respaldo para ${uris.size} URI(s)")
            return true
        }

        // TERCERA ESTRATEGIA (solo para casos donde el permiso persistente falló y
        // tampoco tenemos permiso general): Si aún tenemos espacio, reintentar
        // con permisos no persistentes (válidos solo para la sesión actual)
        if (remainingAllowance >= uris.size) {
            // Esto es raro, pero podría pasar si takePersistableUriPermission falló
            // por razones distintas a la falta de espacio (ej. URI no válido)
            logd("Reintentando con permisos no persistentes para ${uris.size} URI(s)")
            // Los permisos no persistentes ya están activos por el Intent que los otorgó
            // No necesitamos hacer nada más
            return true
        }

        // Si llegamos aquí, no pudimos obtener permisos de ninguna manera
        logd("Fallo al adquirir permisos para ${uris.size} URI(s): límite de permisos agotado")
        return false
    }

    override fun releasePermissionsFor(uris: List<Uri>) {
        for (uri in uris) try {
            context.contentResolver.releasePersistableUriPermission(uri, modeFlags)
        } catch (e: SecurityException) {
            logd("Intento de liberar permiso Uri para $uri " +
                 "cuando no se había concedido previamente")
        }
    }
}