package com.foamplayer.app.data.settings

/**
 * Extra buttons the playback notification can show next to the fixed
 * Previous / Play-Pause / Next controls.
 */
enum class NotificationExtraButton {
    /** Empty slot. */
    NONE,

    /** Cycles the queue's repeat mode (icon follows the current mode). */
    REPEAT,

    /** Toggles shuffle (icon follows the current mode). */
    SHUFFLE,

    /** Pauses playback and dismisses the notification. */
    CLOSE,

    /** Cycles the track score: none -> like -> super like -> dislike -> none. */
    SCORE
}
