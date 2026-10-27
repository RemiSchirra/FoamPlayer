package com.foamplayer.app.ui.screens.equalizer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foamplayer.app.R
import com.foamplayer.app.ui.modals.FoamBottomModal
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * The rename sheet of the presets browser: a text-input bottom sheet that
 * renames a USER preset at once (built-ins cannot be renamed — the save
 * button stays disabled while the text matches the original or is blank).
 * The rename remaps every assignment pointing at the old preset name.
 */
@Composable
internal fun RenamePresetModal(
    visible: Boolean,
    presetName: String?,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(presetName.orEmpty()) }
    LaunchedEffect(visible, presetName) { if (visible) name = presetName.orEmpty() }

    FoamBottomModal(
        visible = visible,
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BasicText(
                text = stringResource(R.string.eq_rename_preset_title),
                style = FoamTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = {
                    BasicText(
                        text = stringResource(R.string.eq_preset_name_hint),
                        style = FoamTheme.typography.body
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.textFieldColors(
                    backgroundColor = FoamTheme.colors.surfaceVariant,
                    focusedIndicatorColor = FoamTheme.accentColor,
                    unfocusedIndicatorColor = FoamTheme.colors.borderOverlay,
                    textColor = FoamTheme.colors.textPrimary,
                    cursorColor = FoamTheme.accentColor
                )
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    BasicText(text = stringResource(R.string.cancel), style = FoamTheme.typography.body)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(FoamTheme.accentColor)
                        .clickable(enabled = name.isNotBlank() && name != presetName) { onRename(name.trim()) }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    BasicText(
                        text = stringResource(R.string.save),
                        style = FoamTheme.typography.body.copy(
                            color = FoamTheme.colors.background,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
