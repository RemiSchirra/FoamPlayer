package com.foamplayer.app.ui.navigation

import androidx.lifecycle.ViewModel
import com.foamplayer.app.update.UpdateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Exposes the singleton [UpdateManager] to composables via hiltViewModel(). */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    val updateManager: UpdateManager
) : ViewModel()
