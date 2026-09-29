package widder.music

/** A key: tonic pitch class and major or (natural) minor. */
data class Key(val tonic: Int, val minor: Boolean) {
    /** "A-Dur", "fis-Moll" (German) or "A major", "F# minor". */
    fun name(notation: Notation = Notation()): String {
        val note = Chord.noteName(tonic, notation)
        return if (minor) "${note.lowercase()}-Moll" else "$note-Dur"
    }

    /** The scale degrees of the key, as intervals above the tonic. */
    val scale: List<Int> get() = if (minor) MINOR_SCALE else MAJOR_SCALE

    companion object {
        private val MAJOR_SCALE = listOf(0, 2, 4, 5, 7, 9, 11)
        private val MINOR_SCALE = listOf(0, 2, 3, 5, 7, 8, 10)

        /** "A", "Am", "A-Dur", "a-Moll", "fis-moll", "Es" ... */
        fun parse(text: String, german: Boolean = false): Key? {
            val t = text.trim()
            if (t.isEmpty()) return null
            val lower = t.lowercase()
            val minorWord = lower.endsWith("moll") || lower.endsWith("minor")
            val stripped = t.replace(Regex("(?i)[- ]?(dur|moll|major|minor)$"), "")
            val chord = Chord.parse(stripped, german) ?: return null
            return Key(chord.root, minorWord || chord.quality.isMinor)
        }
    }
}

/**
 * Roman numerals ("Stufen"): which degree of the key a chord stands on. Upper case for major
 * chords, lower case for minor and diminished ones; inversions as "/3" (third in the bass)
 * and "/5" (fifth in the bass).
 */
object Harmony {
    private val NUMERALS = listOf("I", "II", "III", "IV", "V", "VI", "VII")

    /** The diatonic triad on each degree: 0 major, 1 minor, 2 diminished. */
    private val MAJOR_TRIADS = listOf(0, 1, 1, 0, 0, 1, 2)
    private val MINOR_TRIADS = listOf(1, 2, 0, 1, 1, 0, 0)

    private fun triadType(q: Quality): Int? = when {
        q == Quality.DIM || q == Quality.HALF_DIM || q == Quality.DIM7 -> 2
        q.isMinor -> 1
        4 in q.intervals -> 0
        else -> null // sus, power chord, octave: no third
    }

    /** Degree index and accidental ("", "b", "#") of [pc] in [key]. */
    private fun degree(pc: Int, key: Key): Pair<Int, String> {
        val interval = ((pc - key.tonic) % 12 + 12) % 12
        val scale = key.scale
        scale.indexOf(interval).takeIf { it >= 0 }?.let { return it to "" }
        // Outside the scale: a major key flattens (bIII, bVI, bVII), a minor key sharpens the
        // degree below, except the raised seventh which is written as plain VII.
        if (key.minor && interval == 11) return 6 to ""
        // The Neapolitan is bII in both modes.
        if (interval == 1) return 1 to "b"
        val below = scale.indexOf((interval + 11) % 12)
        val above = scale.indexOf((interval + 1) % 12)
        return if (!key.minor && above >= 0) above to "b" else if (below >= 0) below to "#" else above to "b"
    }

    fun numeral(chord: Chord, key: Key): String {
        val (index, accidental) = degree(chord.root, key)
        val lower = chord.quality.isMinor
        val roman = NUMERALS[index].let { if (lower) it.lowercase() else it }
        val suffix = when (chord.quality) {
            Quality.MAJOR, Quality.MINOR -> ""
            Quality.DIM -> "°"
            Quality.AUG -> "+"
            Quality.DIM7 -> "°7"
            Quality.HALF_DIM -> "ø7"
            Quality.AUG7 -> "+7"
            Quality.MINOR_MAJ7 -> "maj7"
            Quality.MINOR_SIX -> "6"
            Quality.MINOR7 -> "7"
            Quality.MINOR_ADD9 -> "add9"
            Quality.MINOR9 -> "9"
            Quality.OCTAVE -> "-Okt"
            else -> chord.quality.symbol
        }
        val inversion = chord.bass?.let { bass ->
            when (((bass - chord.root) % 12 + 12) % 12) {
                3, 4 -> "/3"
                6, 7, 8 -> "/5"
                9, 10, 11 -> "/7"
                2 -> "/9"
                5 -> "/4"
                else -> "/" + Chord.noteName(bass)
            }
        } ?: ""
        return accidental + roman + suffix + inversion
    }

    /**
     * The key the chords fit best. Only which chords occur counts, not their order, so showing
     * the numerals before an answer does not give away which chord comes first.
     */
    fun detectKey(chords: List<Chord>): Key? {
        if (chords.isEmpty()) return null
        var best: Key? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (minor in listOf(false, true)) {
            for (tonic in 0 until 12) {
                val key = Key(tonic, minor)
                val triads = if (minor) MINOR_TRIADS else MAJOR_TRIADS
                var score = 0.0
                for (c in chords.distinctBy { it.key }) {
                    val interval = ((c.root - tonic) % 12 + 12) % 12
                    val index = key.scale.indexOf(interval)
                    if (index < 0) continue
                    val type = triadType(c.quality)
                    // In minor the dominant is usually major (harmonic minor: E7 in A minor).
                    val raisedDominant = minor && index == 4 && type == 0
                    score += when {
                        type == null -> 1.5
                        type == triads[index] || raisedDominant -> 2.0
                        else -> 0.5
                    }
                    if (interval == 0 && (type == null || type == triads[0])) score += 0.6
                }
                // Ties (C major / A minor) go to major.
                if (minor) score -= 0.01
                if (score > bestScore) {
                    bestScore = score
                    best = key
                }
            }
        }
        return best
    }
}
