package widder.music

/**
 * Intervals up to the double octave (Quintdezime), by their German names. They are answers like
 * chords: "kleine Terz" in a file name or as a tile.
 */
object Interval {
    /** The name of each interval, by its number of semitones. */
    val NAMES = listOf(
        "Prime", "kleine Sekunde", "große Sekunde", "kleine Terz", "große Terz", "Quarte", "Tritonus",
        "Quinte", "kleine Sexte", "große Sexte", "kleine Septime", "große Septime", "Oktave",
        "kleine None", "große None", "kleine Dezime", "große Dezime", "Undezime", "übermäßige Undezime",
        "Duodezime", "kleine Tredezime", "große Tredezime", "kleine Quartdezime", "große Quartdezime", "Quintdezime",
    )

    private val ALIASES = mapOf(
        "reine prime" to 0, "reine quarte" to 5, "übermäßige quarte" to 6, "verminderte quinte" to 6,
        "reine quinte" to 7, "reine oktave" to 12, "reine undezime" to 17, "reine duodezime" to 19,
        "reine quintdezime" to 24, "doppeloktave" to 24,
    )

    /** First words of two-word names: "kleine Terz" in a file name is one answer, not two. */
    private val PREFIXES = setOf("kleine", "große", "reine", "übermäßige", "verminderte").map(::normalize).toSet()

    private fun normalize(text: String) = text.trim().lowercase().replace('ß', 's').replace("ss", "s").replace(Regex("\\s+"), " ")

    private val BY_NAME: Map<String, Int> =
        (NAMES.withIndex().associate { (i, n) -> normalize(n) to i } + ALIASES.mapKeys { normalize(it.key) })

    fun name(semitones: Int): String = NAMES[semitones]

    /** Semitones of "kleine Terz", "grosse Sexte", "Quinte" ..., or null if it is no interval. */
    fun parse(text: String): Int? = BY_NAME[normalize(text)]

    /** Joins "kleine" "Terz" into "kleine Terz"; everything else stays as it is. */
    fun joinWords(words: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            val next = words.getOrNull(i + 1)
            if (next != null && normalize(words[i]) in PREFIXES && parse("${words[i]} $next") != null) {
                out += "${words[i]} $next"
                i += 2
            } else {
                out += words[i++]
            }
        }
        return out
    }
}
