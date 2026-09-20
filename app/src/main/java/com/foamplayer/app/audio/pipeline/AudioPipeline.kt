package com.foamplayer.app.audio.pipeline

import androidx.compose.runtime.Immutable

/** Visual status of one pipeline stage. */
enum class PipelineStageStatus {
    /** The stage is processing samples. */
    ACTIVE,

    /** The stage is transparent (passthrough / nothing to do). */
    BYPASSED,

    /** The stage degrades the signal (resampling, quantization...). */
    WARN
}

/** One node of the audio pipeline visualization. */
@Immutable
data class PipelineStage(
    val name: String,
    val status: PipelineStageStatus,
    val headline: String,
    val details: List<String>
)

/** The whole rendering path of the currently playing track, for the pipeline modal. */
@Immutable
data class AudioPipeline(
    val stages: List<PipelineStage>
)
