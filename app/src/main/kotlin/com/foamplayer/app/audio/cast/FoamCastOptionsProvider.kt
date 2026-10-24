package com.foamplayer.app.audio.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Cast SDK entry point declared in the manifest
 * (`com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME`).
 *
 * FoamPlayer casts to the Default Media Receiver, so no Cast developer account
 * or registered application id is required: the receiver is a plain HTML5 player
 * that accepts a media queue over the standard media namespace.
 */
class FoamCastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(DefaultMediaReceiverAppId)
            // Closing the session must stop the TV playback, not leave it orphaned.
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    companion object {
        /** Well-known id of the Cast Default Media Receiver. */
        const val DefaultMediaReceiverAppId = "CC1AD845"
    }
}
