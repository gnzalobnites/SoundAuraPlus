/*
 * This file is part of SoundAura, which is released under the terms of the Apache
 * License 2.0. See license.md in the project's root directory to see the full license.
 */

package com.gnzalobnites.soundauraplus.addbutton

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.gnzalobnites.soundauraplus.model.UriPermissionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * ViewModel para SystemFileChooser.
 * Wrapper necesario porque hiltViewModel() solo funciona con subclases de ViewModel.
 */
@HiltViewModel
class FileChooserViewModel @Inject constructor(
    private val permissionManager: UriPermissionManager
) : ViewModel() {

    suspend fun acquirePermissions(uris: List<Uri>): UriPermissionManager.PermissionResult {
        if (uris.isEmpty()) {
            return UriPermissionManager.PermissionResult(emptyList(), emptyList())
        }
        return permissionManager.acquirePersistablePermissions(uris)
    }
}