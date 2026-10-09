package widder

import widder.music.AnswerParser
import widder.music.Chord
import widder.music.Notation
import widder.music.Quality
import widder.music.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChordTest {
    private fun keys(name: String, german: Boolean = false) = AnswerParser.parse(name, german).map { it.key }
    private fun texts(name: String) = AnswerParser.parse(name).map { it.text }
    private fun chord(text: String, german: Boolean = false) = assertNotNull(Chord.parse(text, german), text)

    @Test
    fun parsesPlainSpellings() {
        assertEquals(Chord(9, Quality.MAJOR), chord("A"))
        assertEquals(Chord(5, Quality.MINOR), chord("Fmin"))
        assertEquals(Chord(5, Quality.MINOR), chord("Fm"))
        assertEquals(Chord(5, Quality.MINOR), chord("F-"))
        assertEquals(Chord(6, Quality.MINOR7), chord("F#m7"))
        assertEquals(Chord(10, Quality.MAJOR), chord("Bb"))
        assertEquals(Chord(0, Quality.MAJ7), chord("Cmaj7"))
        assertEquals(Chord(0, Quality.MAJ7), chord("CM7"))
        assertEquals(Chord(0, Quality.MINOR7), chord("Cm7"))
        assertEquals(Chord(11, Quality.HALF_DIM), chord("Bø"))
        assertEquals(Chord(4, Quality.SUS4), chord("Esus4"))
        assertEquals(Chord(9, Quality.SUS4), chord("Asus"))
        assertEquals(Chord(0, Quality.MAJOR, bass = 4), chord("C/E"))
        assertNull(Chord.parse("take"))
        assertNull(Chord.parse("Hallo"))
    }

    @Test
    fun starterKitNamesAreChords() {
        val kit = widder.ui.StarterKits.CHORDS.map { chord(it) }
        // All different, so every tile in the kit is its own answer.
        assertEquals(kit.size, kit.map { it.key }.toSet().size)
        assertEquals(Chord(9, Quality.MINOR_ADD9), chord("Am(add9)"))
        assertEquals(Chord(9, Quality.ADD9), chord("Amaj(add9)"))
        assertEquals(Chord(9, Quality.POWER), chord("Apowerchord"))
        assertEquals(Chord(9, Quality.OCTAVE), chord("A-Oktave"))
        assertEquals(Chord(9, Quality.MAJOR_THIRD), chord("Amaj(no5)"))
        assertEquals(Chord(9, Quality.MINOR_THIRD), chord("Amin(no5)"))
        assertEquals(Chord(9, Quality.MINOR_THIRD), chord("A-Mollterz"))
        assertEquals("Am(no5)", chord("Amin(no5)").name())
        assertEquals("Amin(no5)", chord("Am(no5)").name(widder.music.Notation(minorAsMin = true)))
        // Two notes from a MIDI file stay what they were; the thirds are only named, never guessed.
        assertEquals(Chord(9, Quality.MAJOR), Chord.identify(setOf(9, 1), bass = 9))
        assertEquals(Chord(9, Quality.MINOR, bass = 0), chord("Am/C"))
        assertEquals(Chord(9, Quality.MAJOR, bass = 4), chord("A/E"))
        assertEquals(chord("A").key, chord("A/A").key)
        assertEquals(chord("A").key, chord("Amaj").key)
    }

    @Test
    fun readsGermanNames() {
        assertEquals(Chord(11, Quality.MAJOR), chord("H"))
        assertEquals(Chord(11, Quality.MINOR), chord("Hmoll"))
        assertEquals(Chord(6, Quality.MAJOR), chord("Fis"))
        assertEquals(Chord(3, Quality.MAJOR), chord("Es"))
        assertEquals(Chord(8, Quality.MAJOR), chord("As"))
        assertEquals(Chord(1, Quality.MINOR), chord("Cism"))
        assertEquals(Chord(1, Quality.MAJOR), chord("Des-Dur"))
        assertEquals(Chord(10, Quality.MAJOR), chord("B", german = true))
        assertEquals(Chord(11, Quality.MAJOR), chord("B", german = false))
    }

    @Test
    fun fileNamesBecomeAnswers() {
        assertEquals(texts("A D G F.wav"), listOf("A", "D", "G", "F"))
        assertEquals(texts("Fmin Gmin.mid"), listOf("Fmin", "Gmin"))
        assertEquals(keys("Fmin Gmin.wav"), keys("Fm Gm.mp3"))
        assertEquals(texts("Am_F_C_G (2).wav"), listOf("Am", "F", "C", "G"))
        assertEquals(texts("C - G - Am - F take 3.m4a"), listOf("C", "G", "Am", "F", "take"))
        assertEquals(texts("ADGF.wav"), listOf("A", "D", "G", "F"))
        assertEquals(texts("FminGmin.wav"), listOf("Fmin", "Gmin"))
        assertEquals(texts("Cmaj7.wav"), listOf("Cmaj7"))
        assertEquals(texts("Quinte Terz.wav"), listOf("Quinte", "Terz"))
        assertEquals(texts("Am(add9).mid"), listOf("Am(add9)"))
        assertEquals(texts("Am∕C Am∕E Am(add9).mid"), listOf("Am/C", "Am/E", "Am(add9)"))
        assertEquals(texts("A D G F(2).wav"), listOf("A", "D", "G", "F"))
        assertEquals(texts("Son Clave [live].wav"), listOf("Son", "Clave"))
    }

    @Test
    fun sameChordWrittenDifferentlyMatches() {
        val a = AnswerParser.parse("Fmin Gmin.wav")
        val b = listOf(Token.of("Fm"), Token.of("G-"))
        assertTrue(AnswerParser.matches(a, b))
    }

    @Test
    fun identifiesChordsFromNotes() {
        assertEquals("C", Chord.identify(setOf(0, 4, 7), 0)?.name())
        assertEquals("Am", Chord.identify(setOf(9, 0, 4), 9)?.name())
        assertEquals("G7", Chord.identify(setOf(7, 11, 2, 5), 7)?.name())
        assertEquals("Am7", Chord.identify(setOf(9, 0, 4, 7), 9)?.name())
        assertEquals("C6", Chord.identify(setOf(9, 0, 4, 7), 0)?.name())
        assertEquals("Fmin", Chord.identify(setOf(5, 8, 0), 5)?.name(Notation(minorAsMin = true)))
        assertEquals("Bdim", Chord.identify(setOf(11, 2, 5), 11)?.name())
        assertEquals("Bb", Chord.identify(setOf(10, 2, 5), 10)?.name())
        assertEquals("F#m", Chord.identify(setOf(6, 9, 1), 6)?.name())
        assertEquals(listOf("Am", "F"), AnswerParser.parseText("Am F").map { it.text })
        assertEquals("Hm", Chord(11, Quality.MINOR).name(Notation(german = true)))
        assertEquals("B", Chord(10, Quality.MAJOR).name(Notation(german = true)))
    }
}
