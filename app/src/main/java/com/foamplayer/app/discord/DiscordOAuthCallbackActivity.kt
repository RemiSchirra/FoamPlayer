package com.foamplayer.app.discord

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.foamplayer.discord.DiscordApi
import com.foamplayer.discord.DiscordAuthCoordinator

class DiscordOAuthCallbackActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        finish()
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data?.toString() ?: return
        if (DiscordApi.isRedirect(uri)) {
            DiscordAuthCoordinator.emit(uri)
        }
    }
}
