package com.foamplayer.app.ui.modals

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.foamplayer.app.audio.bitperfect.AudioOutputInfo
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.ui.components.FidelityScoreCard
import com.foamplayer.app.ui.theme.FoamDimens

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
            FidelityScoreCard(
                score = outputInfo?.fidelity,
                hasTrack = track != null
            )

            Spacer(modifier = Modifier.height(FoamDimens.paddingMedium))

            AudioPipelineContent(
                outputInfo = outputInfo,
                currentTrack = track
            )
        }
    }
}
