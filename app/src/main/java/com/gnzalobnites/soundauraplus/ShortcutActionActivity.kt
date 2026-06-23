package com.gnzalobnites.soundauraplus

import android.app.Activity
import android.os.Bundle
import com.gnzalobnites.soundauraplus.service.PlayerService

class ShortcutActionActivity : Activity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Usamos el binder de PlayerService para alternar la reproducción.
        // Si el servicio no está corriendo, iniciamos la reproducción directamente.
        PlayerService.binder?.toggleIsPlaying() ?: run {
            startService(PlayerService.playIntent(this))
        }
        
        // Cerramos la actividad inmediatamente para dar el efecto de acción rápida
        finish()
    }
}
