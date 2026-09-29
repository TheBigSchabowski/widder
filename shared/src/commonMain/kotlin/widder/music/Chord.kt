package widder.music

import kotlin.random.Random

/** A chord type: its intervals above the root and the ways people write it. */
enum class Quality(
    val intervals: List<Int>,
    /** How the app writes it; `m` becomes `min` when that is preferred. */
    val symbol: String,
    /** Accepted spellings, compared case-insensitively except where [caseSensitive] lists them. */
    val aliases: List<String>,
    val caseSensitive: List<String> = emptyList(),
) {
    MAJOR(listOf(0, 4, 7), "", listOf("", "maj", "major", "dur", "-dur", "ma")),
    /** Root and its octave: a single pitch class, so it is never guessed from notes, only named. */
    OCTAVE(listOf(0, 12), "-Oktave", listOf("-oktave", "oktave", "octave", "-octave", "8va")),
    MINOR(listOf(0, 3, 7), "m", listOf("m", "min", "mi", "-", "minor", "moll", "-moll"), listOf("m")),
    DIM(listOf(0, 3, 6), "dim", listOf("dim", "°", "o", "vermindert")),
    AUG(listOf(0, 4, 8), "aug", listOf("aug", "+", "übermäßig")),
    SUS2(listOf(0, 2, 7), "sus2", listOf("sus2")),
    SUS4(listOf(0, 5, 7), "sus4", listOf("sus4", "sus")),
    POWER(listOf(0, 7), "5", listOf("5", "powerchord", "power", "-powerchord")),
    SIX(listOf(0, 4, 7, 9), "6", listOf("6", "maj6")),
    MINOR_SIX(listOf(0, 3, 7, 9), "m6", listOf("m6", "min6", "-6")),
    DOM7(listOf(0, 4, 7, 10), "7", listOf("7", "dom7")),
    MAJ7(listOf(0, 4, 7, 11), "maj7", listOf("maj7", "Δ7", "Δ", "ma7", "j7", "^7"), listOf("M7")),
    MINOR7(listOf(0, 3, 7, 10), "m7", listOf("m7", "min7", "mi7", "-7"), listOf("m7")),
    MINOR_MAJ7(listOf(0, 3, 7, 11), "mMaj7", listOf("mmaj7", "m(maj7)", "minmaj7", "-maj7", "mΔ7")),
    HALF_DIM(listOf(0, 3, 6, 10), "m7b5", listOf("m7b5", "ø", "ø7", "min7b5", "-7b5")),
    DIM7(listOf(0, 3, 6, 9), "dim7", listOf("dim7", "°7", "o7")),
    AUG7(listOf(0, 4, 8, 10), "7#5", listOf("7#5", "aug7", "+7")),
    SEVEN_SUS4(listOf(0, 5, 7, 10), "7sus4", listOf("7sus4", "7sus")),
    ADD9(listOf(0, 2, 4, 7), "add9", listOf("add9", "add2", "(add9)", "majadd9", "maj(add9)", "maj add9")),
    MINOR_ADD9(listOf(0, 2, 3, 7), "madd9", listOf("madd9", "minadd9", "m(add9)", "min(add9)")),
    DOM9(listOf(0, 2, 4, 7, 10), "9", listOf("9")),
    MAJ9(listOf(0, 2, 4, 7, 11), "maj9", listOf("maj9", "Δ9"), listOf("M9")),
    MINOR9(listOf(0, 2, 3, 7, 10), "m9", listOf("m9", "min9", "-9"), listOf("m9")),
    ;

    val isMinor: Boolean get() = 3 in intervals && 4 !in intervals

    companion object {
        fun parse(suffix: String): Quality? {
            val s = suffix.trim()
            entries.firstOrNull { s in it.caseSensitive }?.let { return it }
            val lower = s.lowercase()
            // "M7"/"M9" are major, but "M" alone is too easy to mean minor to guess.
            return entries.firstOrNull { q -> q.aliases.any { it.lowercase() == lower } }
        }
    }
}

enum class Spelling { MIXED, SHARPS, FLATS }

/** How note names are written. MIXED is what lead sheets use: Bb, Eb, Ab, Db but F#. */
data class Notation(val german: Boolean = false, val minorAsMin: Boolean = false, val spelling: Spelling = Spelling.MIXED)

data class Chord(val root: Int, val quality: Quality, val bass: Int? = null) {
    val pitchClasses: Set<Int> get() = quality.intervals.map { (root + it) % 12 }.toSet() + listOfNotNull(bass)

    /** Equal chords have equal keys, however they were written. */
    val key: String get() = "$root:${quality.name}" + (bass?.let { "/$it" } ?: "")

    fun name(notation: Notation = Notation()): String {
        val q = if (notation.minorAsMin && quality.symbol.startsWith("m") && !quality.symbol.startsWith("maj")) {
            "min" + quality.symbol.removePrefix("m")
        } else {
            quality.symbol
        }
        return noteName(root, notation) + q + (bass?.let { "/" + noteName(it, notation) } ?: "")
    }

    override fun toString(): String = name()

    companion object {
        private val SEPARATOR = Regex("[\\s_]+")

        /**
         * Reads "A", "Fmin", "F#m7", "Bb", "Hmoll", "Fis", "Es-Dur", "Cmaj7/E" ...
         * With [german] notation, B is B flat and H is B natural; H is always B natural.
         */
        fun parse(text: String, german: Boolean = false): Chord? {
            val t = text.trim().replace('♯', '#').replace('♭', 'b').replace(SEPARATOR, "")
            if (t.isEmpty()) return null
            val slash = t.lastIndexOf('/')
            if (slash > 0) {
                val main = parse(t.substring(0, slash), german) ?: return null
                val bass = parseRoot(t.substring(slash + 1), german).firstOrNull { it.second.isEmpty() }?.first ?: return null
                return main.copy(bass = bass.takeIf { it != main.root })
            }
            for ((root, rest) in parseRoot(t, german)) {
                Quality.parse(rest)?.let { return Chord(root, it) }
            }
            return null
        }

        /** Every way the start of [t] can be read as a note name, with what is left over. */
        private fun parseRoot(t: String, german: Boolean): List<Pair<Int, String>> {
            val letter = t.firstOrNull()?.uppercaseChar() ?: return emptyList()
            val natural = when (letter) {
                'C' -> 0; 'D' -> 2; 'E' -> 4; 'F' -> 5; 'G' -> 7; 'A' -> 9
                'B' -> if (german) 10 else 11
                'H' -> 11
                else -> return emptyList()
            }
            val rest = t.substring(1)
            val options = mutableListOf<Pair<Int, String>>()
            fun add(shift: Int, len: Int) {
                if (rest.length >= len) options += (natural + shift + 12) % 12 to rest.substring(len)
            }
            val lower = rest.lowercase()
            if (rest.startsWith("##")) add(2, 2)
            if (rest.startsWith("bb") && letter != 'B') add(-2, 2)
            if (rest.startsWith("#")) add(1, 1)
            if (rest.startsWith("b")) add(-1, 1)
            if (lower.startsWith("is")) add(1, 2)
            if (lower.startsWith("es")) add(-1, 2)
            // "As", "Es": the e of "es" is part of the letter.
            if (lower.startsWith("s") && (letter == 'A' || letter == 'E')) add(-1, 1)
            add(0, 0)
            return options
        }

        private val SHARP_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        private val FLAT_NAMES = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
        private val MIXED_NAMES = listOf("C", "Db", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

        fun noteName(pc: Int, notation: Notation = Notation()): String {
            val names = when (notation.spelling) {
                Spelling.MIXED -> MIXED_NAMES
                Spelling.SHARPS -> SHARP_NAMES
                Spelling.FLATS -> FLAT_NAMES
            }
            val name = names[((pc % 12) + 12) % 12]
            if (!notation.german) return name
            return when {
                name == "B" -> "H"
                name == "Bb" -> "B"
                name.endsWith("#") -> name.dropLast(1) + "is"
                name == "Eb" -> "Es"
                name == "Ab" -> "As"
                name.endsWith("b") -> name.dropLast(1) + "es"
                else -> name
            }
        }

        /**
         * Names the chord made of [pitchClasses], preferring [bass] as root, or null if nothing fits.
         * Missing fifths are tolerated; extra notes cost more than missing ones.
         */
        fun identify(pitchClasses: Set<Int>, bass: Int? = null): Chord? {
            if (pitchClasses.size < 2) return null
            var best: Chord? = null
            var bestScore = Double.NEGATIVE_INFINITY
            for (root in pitchClasses) {
                for ((index, quality) in Quality.entries.withIndex()) {
                    if (quality == Quality.POWER && pitchClasses.size > 2) continue
                    val tones = quality.intervals.map { (root + it) % 12 }.toSet()
                    val fifth = (root + 7) % 12
                    val omittedFifth = fifth in tones && fifth !in pitchClasses
                    if (tones.any { it !in pitchClasses && it != fifth }) continue
                    val present = tones.count { it in pitchClasses }
                    if (present < 2) continue
                    val extra = pitchClasses.count { it !in tones }
                    val score = present - 2.0 * extra - (if (omittedFifth) 0.6 else 0.0) +
                        (if (root == bass) 0.8 else 0.0) - index * 0.01
                    if (score > bestScore) {
                        bestScore = score
                        best = Chord(root, quality)
                    }
                }
            }
            return best
        }
    }
}

/** One answer token: the chord it names if it is one, otherwise the plain text. */
data class Token(val text: String, val chord: Chord?) {
    val key: String get() = chord?.key ?: text.trim().lowercase()

    companion object {
        fun of(text: String, german: Boolean = false) = Token(text, Chord.parse(text, german))
    }
}

/**
 * Turns a file name into the answer: "A D G F.wav" is A, D, G, F; "Fmin Gmin.mid" is Fmin, Gmin.
 * Spaces, underscores, commas, semicolons, pipes, plus signs and " - " separate; things in
 * brackets and bare numbers ("take 2", "(1)") are ignored. A single run like "ADGF" or
 * "AmFCG" is split at the note letters when that yields only chords.
 */
object AnswerParser {
    // Escaped fully: Android's ICU regex rejects "[^]]" and a bare "}".
    private val BRACKETS = Regex("\\([^)]*\\)|\\[[^\\]]*\\]|\\{[^}]*\\}")
    private val SEPARATORS = Regex(" - |–|—|[\\s_,;|+]+")
    private val NUMBER = Regex("\\d+")
    private val DASHES = Regex("[-–—]+")

    /** Stands in for "/" in file names. */
    const val FILE_SLASH = '\u2215'

    /** "Am/C" -> "Am∕C", and characters no file system takes are dropped. */
    fun fileSafe(name: String): String =
        name.replace('/', FILE_SLASH).replace(Regex("[\\\\:*?\"<>|]"), "").trim()

    fun parse(fileName: String, german: Boolean = false): List<Token> {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        val stem = if ('.' in base && base.substringAfterLast('.').length in 2..4) base.substringBeforeLast('.') else base
        return parseText(stem, german)
    }

    /** Like [parse], for typed text that has no file extension. */
    fun parseText(text: String, german: Boolean = false): List<Token> {
        // File names cannot contain "/": "Am∕C.mid" uses the division slash instead.
        val stem = text.replace(FILE_SLASH, '/').replace('\u2044', '/')
        val words = stem.replace(BRACKETS, " ").split(SEPARATORS).map { it.trim() }
            .filter { it.isNotEmpty() && !NUMBER.matches(it) && !DASHES.matches(it) }
        if (words.size == 1) {
            val only = words[0]
            if (Chord.parse(only, german) == null) splitRun(only, german)?.let { return it }
        }
        return words.map { Token.of(it, german) }
    }

    /** "ADGF" -> A D G F, "FminGmin" -> Fmin Gmin; null if a piece is not a chord. */
    private fun splitRun(run: String, german: Boolean): List<Token>? {
        val starts = run.indices.filter { i -> run[i] in "ABCDEFGH" }
        if (starts.size < 2 || starts[0] != 0) return null
        val pieces = starts.mapIndexed { n, start -> run.substring(start, starts.getOrElse(n + 1) { run.length }) }
        val tokens = pieces.map { Token.of(it, german) }
        return tokens.takeIf { list -> list.all { it.chord != null } }
    }

    /** Same answer, however the chords were written. */
    fun matches(a: List<Token>, b: List<Token>): Boolean = a.map { it.key } == b.map { it.key }
}

/** Wrong tiles for the pool, close enough to the right ones to be a real choice. */
object Distractors {
    fun make(answer: List<Token>, count: Int, notation: Notation, random: Random = Random): List<Token> {
        if (count <= 0) return emptyList()
        val chords = answer.mapNotNull { it.chord }
        val taken = answer.map { it.key }.toMutableSet()
        val candidates = mutableListOf<Chord>()
        for (c in chords) {
            val other = when {
                c.quality == Quality.MAJOR -> Quality.MINOR
                c.quality == Quality.MINOR -> Quality.MAJOR
                c.quality.isMinor -> Quality.MINOR7
                else -> Quality.DOM7
            }
            candidates += Chord(c.root, other)
            candidates += Chord((c.root + 2) % 12, c.quality)
            candidates += Chord((c.root + 10) % 12, c.quality)
            candidates += Chord((c.root + 5) % 12, c.quality)
            candidates += Chord((c.root + 7) % 12, c.quality)
        }
        if (chords.isEmpty()) return emptyList()
        val sharps = answer.any { t -> t.text.length > 1 && (t.text[1] == '#' || t.text.drop(1).startsWith("is")) }
        val flats = answer.any { t -> t.text.length > 1 && (t.text[1] == 'b' || t.text.drop(1).startsWith("es")) }
        val style = notation.copy(
            spelling = if (sharps && !flats) Spelling.SHARPS else if (flats && !sharps) Spelling.FLATS else Spelling.MIXED,
            minorAsMin = answer.any { it.text.contains("min") } || notation.minorAsMin,
        )
        return candidates.shuffled(random).filter { taken.add(it.key) }.take(count)
            .map { Token(it.name(style), it) }
    }
}
