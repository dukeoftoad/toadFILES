package com.toadfiles.app.domain

/**
 * v1.11 font picker (Appearance tab). [SYSTEM] follows the device's default
 * typeface; every other entry is bundled under res/font as static weights,
 * Latin-subsetted by scripts/make_toadfiles_fonts.py.
 *
 * [TOAD] is the unique toad font: Baloo 2 (SIL OFL, see docs/licenses),
 * instanced to static weights and renamed to "Toad" — the OFL requires a
 * renamed Modified Version, and the chunky rounded letterforms suit the
 * mascot. Defaults keep the shipped look ([DEFAULT]); every pick is the
 * user's.
 */
enum class AppFont(val label: String, val description: String) {
    SYSTEM("System", "Your device's default typeface"),
    MANROPE("Manrope", "Rounded and friendly — the toadFILES look"),
    TOAD("Toad", "Chunky, bouncy letters shaped like the mascot"),
    INTER("Inter", "Crisp and neutral, built for screens"),
    SPACE_GROTESK("Space Grotesk", "Quirky technical shapes with wide caps"),
    JETBRAINS_MONO("JetBrains Mono", "Monospace — every glyph the same width"),
    ;

    companion object {
        /** Preserves the v1.11 redesign's bundled-Manrope look for upgraders. */
        val DEFAULT = MANROPE
    }
}
