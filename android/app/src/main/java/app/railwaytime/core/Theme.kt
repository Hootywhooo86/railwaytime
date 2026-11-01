package app.railwaytime.core

/**
 * Colours, 128bit-family style: a dark (or light) base plus two accents. [accent] is the
 * clock, likely train and main buttons; [accent2] the labels and location; [alert] warnings.
 */
data class Palette(
    val id: String,
    val name: String,
    val bg: Int,
    val panel: Int,
    val ink: Int,
    val muted: Int,
    val accent: Int,
    val accent2: Int,
    val alert: Int,
) {
    val isLight: Boolean get() = luminance(bg) > 0.5

    companion object {
        fun luminance(c: Int): Double {
            fun ch(shift: Int) = ((c shr shift) and 0xFF) / 255.0
            return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
        }
    }
}

object Themes {
    private fun c(hex: Long) = (0xFF000000 or hex).toInt()

    /** The 128bit family look (the landing page's palette) — the default. */
    val DEFAULT = Palette("128bit", "128bit", c(0x0B0B16), c(0x141428), c(0xF4F1FF), c(0x9A97B8), c(0xFF9F1C), c(0x2DD4BF), c(0xFF5D8F))

    val PRESETS = listOf(
        DEFAULT,
        Palette("cn", "CN Red", c(0x0F0F12), c(0x1C1C22), c(0xF5F5F7), c(0xA1A1AA), c(0xE8364D), c(0xD4D4D8), c(0xFF8A3D)),
        Palette("signal", "Signal", c(0x07110B), c(0x0F1F16), c(0xECFDF3), c(0x86A893), c(0x4ADE80), c(0xFACC15), c(0xF87171)),
        Palette("night", "Night Shift", c(0x05070F), c(0x0E1424), c(0xE6EDFF), c(0x8B97B5), c(0x60A5FA), c(0xA78BFA), c(0xF472B6)),
        Palette("arcade", "Arcade", c(0x0D0221), c(0x1A0B3A), c(0xFFF1FB), c(0xA99BC9), c(0xFF5D8F), c(0x22D3EE), c(0xFACC15)),
        Palette("amber", "Amber CRT", c(0x000000), c(0x120C00), c(0xFFE7B0), c(0xB08A4A), c(0xFFB000), c(0xFF6A00), c(0xFF3B3B)),
        Palette("day", "Daylight", c(0xF7F5EF), c(0xFFFFFF), c(0x1B1B2A), c(0x6B6880), c(0xE07A00), c(0x0E9F8E), c(0xD6336C)),
    )

    /** Swatches for picking your own accents. */
    val SWATCHES = listOf(
        c(0xFF9F1C), c(0x2DD4BF), c(0xFF5D8F), c(0xE8364D), c(0x4ADE80), c(0xFACC15),
        c(0x60A5FA), c(0xA78BFA), c(0x22D3EE), c(0xFB923C), c(0xF4F1FF), c(0x94A3B8),
    )

    /** A preset, with your own accent colours on top (0 = keep the preset's). */
    fun resolve(id: String?, accent: Int = 0, accent2: Int = 0): Palette {
        val base = PRESETS.firstOrNull { it.id == id } ?: DEFAULT
        return base.copy(
            accent = if (accent != 0) accent else base.accent,
            accent2 = if (accent2 != 0) accent2 else base.accent2,
        )
    }
}
