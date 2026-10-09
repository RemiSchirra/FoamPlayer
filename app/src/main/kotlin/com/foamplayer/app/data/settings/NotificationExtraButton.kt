package com.foamplayer.app.data.settings

/**
 * One of the two additional buttons the playback notification can show next to
 * the fixed Previous / Play-Pause / Next controls. [NONE] removes the slot.
 */
enum class NotificationExtraButton {
    /** No button in this slot. */
    NONE,

    /** Cycles the queue's repeat mode (icon follows the current mode). */
    REPEAT,

    /** Toggles shuffle (icon follows the current mode). */
    SHUFFLE,

    /** Cross icon: pauses playback and dismisses the notification. */
    CLOSE,

    /**
     * Toggles the current track's score, cycling
     * empty heart -> like -> super like -> dislike -> empty heart
     * (the icon mirrors the track's current score).
     */
    SCORE
}
