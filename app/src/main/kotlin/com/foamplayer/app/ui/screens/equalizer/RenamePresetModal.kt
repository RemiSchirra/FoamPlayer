package com.foamplayer.app.ui.screens.equalizer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.foamplayer.app.R
import com.foamplayer.app.ui.modals.FoamBottomModal
import com.foamplayer.app.ui.modals.ModalButton
import com.foamplayer.app.ui.modals.PlaylistNameField
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * The rename sheet of the presets browser: the same mini modal that renames
 * a playlist — a pill input prefilled with the current name, full-width
 * Save/Cancel with ripple, and an auto-focused field so the keyboard is
 * already up when the sheet settles (Done submits). Renames a USER preset at
 * once (built-ins cannot be renamed — the save button dims and swallows taps
 * while the text matches the original or is blank). The rename remaps every
 * assignment pointing at the old preset name.
 */
@Composable
internal fun RenamePresetModal(
    visible: Boolean,
    presetName: String?,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Prefilled with the CURRENT name. Keyed on the stored name, not the field
    // value, so user edits survive recompositions but a re-open (or a rename)
    // re-seeds the field with the fresh name; the key is stable while the sheet
    // plays its exit animation, so the text never visibly clears mid-slide.
    var name by remember(presetName) { mutableStateOf(presetName.orEmpty()) }

    // Valid rename: non-blank and different from the original (the engine
    // still rejects duplicates and built-in targets behind the call).
    val canRename = name.isNotBlank() && name != presetName

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(visible) {
        if (visible) focusRequester.requestFocus()
    }

    FoamBottomModal(
        visible = visible,
        onDismissRequest = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)) {
            BasicText(
                text = stringResource(R.string.eq_rename_preset_title),
                style = FoamTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    horizontal = FoamDimens.paddingSmall,
                    vertical = FoamDimens.spacingTiny
                )
            )

            PlaylistNameField(
                value = name,
                onValueChange = { name = it },
                hint = stringResource(R.string.eq_preset_name_hint),
                focusRequester = focusRequester,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (canRename) onRename(name.trim()) }
                )
            )

            ModalButton(
                label = stringResource(R.string.save),
                filled = true,
                enabled = canRename,
                onClick = { onRename(name.trim()) }
            )
            ModalButton(
                label = stringResource(R.string.cancel),
                filled = false,
                onClick = onDismiss
            )
        }
    }
}
