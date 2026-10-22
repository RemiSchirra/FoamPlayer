package com.foamplayer.app.ui.modals

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.foamplayer.app.R
import com.foamplayer.app.lastfm.LastFmCredentialSource
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * Chooses which Last.fm API credentials the app signs requests with: the key
 * bundled with the build, or a custom API key + shared secret. Saving a
 * different key/secret disconnects the current account because Last.fm session
 * keys are scoped to the API account that authorized them.
 */
@Composable
fun LastFmCredentialsModal(
    visible: Boolean,
    currentSource: LastFmCredentialSource,
    appCredentialsConfigured: Boolean,
    customApiKey: String,
    customApiSecret: String,
    onDismiss: () -> Unit,
    onUseAppCredentials: () -> Unit,
    onSaveCustomCredentials: (apiKey: String, apiSecret: String) -> Unit
) {
    var source by remember { mutableStateOf(currentSource) }
    var apiKey by remember { mutableStateOf(customApiKey) }
    var apiSecret by remember { mutableStateOf(customApiSecret) }

    LaunchedEffect(visible) {
        if (visible) {
            source = if (currentSource == LastFmCredentialSource.APP && !appCredentialsConfigured) {
                LastFmCredentialSource.CUSTOM
            } else {
                currentSource
            }
            apiKey = customApiKey
            apiSecret = customApiSecret
        }
    }

    val canSave = when (source) {
        LastFmCredentialSource.APP -> appCredentialsConfigured
        LastFmCredentialSource.CUSTOM -> apiKey.isNotBlank() && apiSecret.isNotBlank()
    }

    fun save() {
        when (source) {
            LastFmCredentialSource.APP -> onUseAppCredentials()
            LastFmCredentialSource.CUSTOM -> onSaveCustomCredentials(apiKey.trim(), apiSecret.trim())
        }
        onDismiss()
    }

    FoamBottomModal(
        visible = visible,
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BasicText(
                text = stringResource(R.string.lastfm_api_key_modal_title),
                style = FoamTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
            BasicText(
                text = stringResource(R.string.lastfm_api_key_modal_desc),
                style = FoamTheme.typography.body
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingStandard))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FoamDimens.itemRounding))
                    .background(FoamTheme.colors.surfaceVariant)
            ) {
                CredentialOptionRow(
                    title = stringResource(R.string.lastfm_api_key_app),
                    description = stringResource(
                        if (appCredentialsConfigured) {
                            R.string.lastfm_api_key_app_desc
                        } else {
                            R.string.lastfm_api_key_app_missing
                        }
                    ),
                    selected = source == LastFmCredentialSource.APP,
                    enabled = appCredentialsConfigured,
                    onClick = { source = LastFmCredentialSource.APP }
                )
                ModalSectionDivider()
                CredentialOptionRow(
                    title = stringResource(R.string.lastfm_api_key_custom),
                    description = stringResource(R.string.lastfm_api_key_custom_desc),
                    selected = source == LastFmCredentialSource.CUSTOM,
                    enabled = true,
                    onClick = { source = LastFmCredentialSource.CUSTOM }
                )
            }

            // The fields grow into place instead of popping in, so switching
            // between the two options reads as one continuous sheet motion.
            AnimatedVisibility(
                visible = source == LastFmCredentialSource.CUSTOM,
                enter = fadeIn(animationSpec = tween(FIELD_FADE_MS)) +
                        expandVertically(
                            animationSpec = tween(FIELD_EXPAND_MS),
                            expandFrom = Alignment.Top
                        ),
                exit = fadeOut(animationSpec = tween(FIELD_FADE_MS)) +
                        shrinkVertically(
                            animationSpec = tween(FIELD_EXPAND_MS),
                            shrinkTowards = Alignment.Top
                        )
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(FoamDimens.spacingStandard))
                    BasicText(
                        text = stringResource(R.string.lastfm_api_key_label),
                        style = FoamTheme.typography.caption
                    )
                    Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
                    CredentialTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        hint = stringResource(R.string.lastfm_api_key_label)
                    )
                    Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))
                    BasicText(
                        text = stringResource(R.string.lastfm_api_secret_label),
                        style = FoamTheme.typography.caption
                    )
                    Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
                    CredentialTextField(
                        value = apiSecret,
                        onValueChange = { apiSecret = it },
                        hint = stringResource(R.string.lastfm_api_secret_label),
                        isPassword = true
                    )
                    Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
                    BasicText(
                        text = stringResource(R.string.lastfm_api_key_create),
                        style = FoamTheme.typography.caption.copy(
                            color = FoamTheme.colors.textSecondary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(FoamDimens.spacingStandard))
            ModalButton(
                label = stringResource(R.string.save),
                filled = true,
                enabled = canSave,
                onClick = { save() }
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
            ModalButton(
                label = stringResource(R.string.cancel),
                filled = false,
                onClick = onDismiss
            )
        }
    }
}

/** One radio option of the credential choice: title, description, ring indicator. */
@Composable
private fun CredentialOptionRow(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = FoamTheme.colors.textPrimary),
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.paddingSmall),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(text = title, style = FoamTheme.typography.titleMedium)
            BasicText(text = description, style = FoamTheme.typography.body)
        }
        Spacer(modifier = Modifier.width(FoamDimens.paddingMedium))
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(
                    width = 2.dp,
                    color = if (selected) FoamTheme.accentColor else FoamTheme.colors.textSecondary,
                    shape = CircleShape
                )
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(FoamTheme.accentColor)
                )
            }
        }
    }
}

/** Pill-shaped single-line input matching the Add Provider modal's fields. */
@Composable
private fun CredentialTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    isPassword: Boolean = false
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(FoamTheme.colors.surfaceVariant)
            .padding(horizontal = FoamDimens.paddingLarge, vertical = FoamDimens.spacingStandard),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            BasicText(
                text = hint,
                style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textSecondary)
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = FoamTheme.typography.body.copy(color = FoamTheme.colors.textPrimary),
            cursorBrush = SolidColor(FoamTheme.accentColor),
            singleLine = true,
            visualTransformation = if (isPassword) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = if (isPassword) {
                KeyboardOptions(keyboardType = KeyboardType.Password)
            } else {
                KeyboardOptions.Default
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private const val FIELD_FADE_MS = 160
private const val FIELD_EXPAND_MS = 220
