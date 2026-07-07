/*
 * This file is part of SoundAura, which is released under the terms of the Apache
 * License 2.0. See license.md in the project's root directory to see the full license.
 */

package com.gnzalobnites.soundauraplus.addbutton

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gnzalobnites.soundauraplus.logd
import kotlinx.coroutines.launch

/**
 * SystemFileChooser - Lanza el selector de archivos del sistema.
 *
 * FLUJO: Seleccion -> Adquisicion de permisos -> Devolver solo URIs validos
 */
@Composable
fun SystemFileChooser(
    fileTypeArgs: Array<String> = arrayOf("audio/*", "application/ogg"),
    onFilesSelected: (List<Uri>) -> Unit,
    onPermissionDenied: (List<Uri>) -> Unit = {}
) {
    val viewModel: FileChooserViewModel = viewModel()
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            if (uris.isEmpty()) {
                onFilesSelected(emptyList())
                return@rememberLauncherForActivityResult
            }

            scope.launch {
                val result = viewModel.acquirePermissions(uris)

                if (result.hasDenied) {
                    logd("URIs rechazados: ${result.denied.size}")
                    onPermissionDenied(result.denied)
                }

                onFilesSelected(result.granted)
            }
        }
    )

    LaunchedEffect(Unit) {
        launcher.launch(fileTypeArgs)
    }
}
