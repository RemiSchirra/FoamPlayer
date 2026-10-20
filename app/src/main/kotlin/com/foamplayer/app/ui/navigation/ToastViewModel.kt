package com.foamplayer.app.ui.navigation

import androidx.lifecycle.ViewModel
import com.foamplayer.app.toast.ToastCenter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Exposes the singleton [ToastCenter] to composables via hiltViewModel(). */
@HiltViewModel
class ToastViewModel @Inject constructor(
    val toastCenter: ToastCenter
) : ViewModel()
