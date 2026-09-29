package widder

import widder.music.Chord
import widder.music.Harmony
import widder.music.Key
import widder.music.Notation
import widder.ui.StarterKits
import kotlin.test.Test
import kotlin.test.assertEquals

class HarmonyTest {
    private fun n(chord: String, key: String) = Harmony.numeral(Chord.parse(chord)!!, Key.parse(key)!!)
    private fun detect(vararg chords: String) = Harmony.detectKey(chords.map { Chord.parse(it)!! })

    @Test
    fun numeralsInMajor() {
        assertEquals("I", n("A", "A"))
        assertEquals("IV", n("D", "A"))
        assertEquals("V7", n("E7", "A"))
        assertEquals("vi", n("F#m", "A"))
        assertEquals("bVII", n("G", "A"))
        assertEquals("bVI", n("F", "A"))
        assertEquals("ii7", n("Dm7", "C"))
        assertEquals("Imaj7", n("Cmaj7", "C"))
        assertEquals("vii°", n("Bdim", "C"))
        assertEquals("viiø7", n("Bm7b5", "C"))
        assertEquals("bII", n("Db", "C"))
    }

    @Test
    fun numeralsInMinorAndInversions() {
        assertEquals("i", n("Am", "a-Moll"))
        assertEquals("III", n("C", "Am"))
        assertEquals("VII", n("G", "Am"))
        assertEquals("V7", n("E7", "Am"))
        assertEquals("I/3", n("A/C#", "A"))
        assertEquals("I/5", n("A/E", "A"))
        assertEquals("i/3", n("Am/C", "A"))
        assertEquals("i/5", n("Am/E", "A"))
        assertEquals("iadd9", n("Am(add9)", "A"))
        assertEquals("I5", n("A5", "A"))
    }

    @Test
    fun wholeStarterKitHasNumerals() {
        val labels = StarterKits.A_CHORDS.map { n(it, "A") }
        assertEquals(listOf("iadd9", "Iadd9", "I7", "Imaj7", "i/3", "i/5", "I", "I/3", "I/5", "I-Okt", "I5"), labels)
    }

    @Test
    fun recognisesTheKeyFromTheChords() {
        assertEquals(Key(9, false), detect("A", "D", "E"))
        assertEquals(Key(0, false), detect("Am", "F", "C", "G"))
        assertEquals(Key(9, true), detect("Am", "Dm", "E7"))
        assertEquals(Key(9, false), detect("A7"))
        assertEquals(Key(9, true), detect("Am/C"))
        // Order does not matter, so the numerals give nothing away before answering.
        assertEquals(detect("G", "C", "D"), detect("D", "G", "C"))
    }

    @Test
    fun keyNames() {
        assertEquals("A-Dur", Key.parse("A")!!.name())
        assertEquals("fis-Moll", Key.parse("fis-moll")!!.name(Notation(german = true)))
        assertEquals(Key(3, false), Key.parse("Es-Dur"))
        assertEquals(Key(6, true), Key.parse("F#m"))
    }
}
