package com.gnzalobnites.soundauraplus.service

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.lifecycle.LifecycleService

/**
 * Módulo que pausa la reproducción automáticamente cuando se desconectan
 * los auriculares y la reanuda cuando se vuelven a conectar.
 *
 * Este módulo utiliza el mecanismo de [autoPauseIf] y [unpauseLocks] de
 * [PlayerService] para gestionar la pausa/reanudación de forma segura.
 *
 * Cuando los auriculares se desconectan:
 *   - Se añade un bloqueo (lock) con la clave "auto_pause_headset_disconnected"
 *   - Si no hay otros bloqueos activos, la reproducción se pausa
 *
 * Cuando los auriculares se vuelven a conectar:
 *   - Se elimina el bloqueo
 *   - Si no hay otros bloqueos activos, la reproducción se reanuda automáticamente
 */
class HeadsetConnectionPlaybackModule(
    private val autoPauseIf: (condition: Boolean, key: String) -> Unit,
) : PlayerService.PlaybackModule {

    private lateinit var audioManager: AudioManager
    private var isHeadsetConnected = false

    override fun onCreate(service: LifecycleService) {
        audioManager = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        isHeadsetConnected = checkHeadsetConnected()
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
    }

    override fun onDestroy(service: LifecycleService) {
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
    }

    /**
     * Verifica si hay al menos un dispositivo de auriculares conectado actualmente.
     */
    private fun checkHeadsetConnected(): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any { isHeadsetDevice(it) }
    }

    /**
     * Determina si un [AudioDeviceInfo] corresponde a un dispositivo de auriculares.
     *
     * Soporta:
     * - Auriculares/cascos con cable (TYPE_WIRED_HEADPHONES, TYPE_WIRED_HEADSET)
     * - Dispositivos Bluetooth A2DP (TYPE_BLUETOOTH_A2DP)
     * - Dispositivos USB (TYPE_USB_HEADSET, TYPE_USB_DEVICE)
     * - Dispositivos Bluetooth LE (TYPE_BLE_HEADSET, disponible desde API 33)
     */
    private fun isHeadsetDevice(deviceInfo: AudioDeviceInfo): Boolean {
        val isStandardHeadset = when (deviceInfo.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE -> true
            else -> false
        }

        val isBleHeadset = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            deviceInfo.type == AudioDeviceInfo.TYPE_BLE_HEADSET

        return isStandardHeadset || isBleHeadset
    }

    /**
     * Callback que reacciona a los cambios en los dispositivos de audio.
     *
     * Cuando se añaden dispositivos, verifica si ahora hay auriculares conectados.
     * Cuando se eliminan, verifica si los auriculares se desconectaron.
     */
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            val wasConnected = isHeadsetConnected
            isHeadsetConnected = checkHeadsetConnected()

            // Si no había auriculares y ahora sí, reanudamos la reproducción
            if (!wasConnected && isHeadsetConnected) {
                autoPauseIf(false, autoPauseHeadsetKey)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            val wasConnected = isHeadsetConnected
            isHeadsetConnected = checkHeadsetConnected()

            // Si había auriculares y se desconectaron, pausamos la reproducción
            if (wasConnected && !isHeadsetConnected) {
                autoPauseIf(true, autoPauseHeadsetKey)
            }
        }
    }

    companion object {
        /**
         * Clave única para el bloqueo de pausa por desconexión de auriculares.
         * Esta clave se usa en [unpauseLocks] para identificar esta condición.
         */
        private const val autoPauseHeadsetKey = "auto_pause_headset_disconnected"
    }
}