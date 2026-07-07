/* This file is part of SoundAura, which is released under the terms of the Apache
 * License 2.0. See license.md in the project's root directory to see the full license. */

package com.gnzalobnites.soundauraplus

import android.content.ComponentName
import android.content.Intent
import android.service.quicksettings.TileService
import android.util.Log
import com.gnzalobnites.soundauraplus.service.PlayerService
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

        // NOTA: Se ha eliminado el inicio de PlaylistRecoveryService.
        // Los permisos persistentes ahora se adquieren en el momento de seleccionar los archivos,
        // por lo que este servicio ya no es necesario y además causaba una condición de carrera
        // con PlayerService al iniciar desde atajos o Quick Settings.
    }
}

fun logd(message: String) = Log.d("SoundAuraTag", message)