package com.foamplayer.app.ui.screens.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foamplayer.app.R
import com.foamplayer.app.audio.bitperfect.AudioOutputInfo
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.ui.components.FidelityScoreCard
import com.foamplayer.app.ui.components.FoamBottomModal
import com.foamplayer.app.ui.screens.settings.AudioPipelineContent
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * Bottom sheet opened from the player's audio-info chip: the live fidelity
 * score card (expandable, identical to the one in Audio settings) on top and
 * the audio pipeline visualization below it.
 */
@Composable
internal fun PlayerAudioInfoModal(
    visible: Boolean,
    onDismiss: () -> Unit,
    outputInfo: AudioOutputInfo?,
    track: TrackEntity?
) {
    FoamBottomModal(
        visible = visible,
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BasicText(
                text = stringResource(R.string.audio_fidelity_score),
                style = FoamTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))

            FidelityScoreCard(
                score = outputInfo?.fidelity,
                hasTrack = track != null
            )

            Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))

            AudioPipelineContent(
                outputInfo = outputInfo,
                currentTrack = track
            )
        }
    }
}
