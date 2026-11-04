package tv.own.owntv.player

import android.content.Context

/**
 * The frame rate each live channel was last measured at, so Auto frame rate can switch the display the
 * moment the channel is opened instead of 5–40 s into the picture.
 *
 * The HDMI re-sync of a mode switch blacks the TV out for a second or two. Made while the channel is still
 * loading, that gap is part of the tune; made after the measurement came in, it interrupts a running
 * picture and reads as a fault. A live stream rarely declares its rate, so without a memory the switch
 * could only ever come late.
 *
 * Only readings the display cannot have capped are kept ([trustworthy]): the frame counter behind a
 * measurement never counts more frames than the display shows, so 60p on a 50 Hz display can read "50".
 */
internal object FrameRateMemory {
    private const val PREFS = "afr_channel_rates"

    /** At least this far from the display's refresh rate, a reading cannot be the display's ceiling. */
    private const val CEILING_MARGIN_HZ = 1.5f

    fun get(context: Context, key: String): Float? =
        prefs(context).getFloat(key, 0f).takeIf { it > 0f }

    fun put(context: Context, key: String, fps: Float) {
        val p = prefs(context)
        if (p.getFloat(key, 0f) != fps) p.edit().putFloat(key, fps).apply()
    }

    /**
     * Whether a [snapped] standard reading is worth remembering on a display running at [displayHz]. Only a
     * reading right at the display's rate is suspect; one above it cannot have been capped, one below
     * neither.
     */
    fun trustworthy(snapped: Float, displayHz: Float): Boolean = kotlin.math.abs(snapped - displayHz) > CEILING_MARGIN_HZ

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
