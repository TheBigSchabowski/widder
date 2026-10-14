package widder.audio

import widder.core.DrumKit
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BundledDrumsTest {
    @Test
    fun acousticKitLoadsFromTheResources() {
        val samples = assertNotNull(DrumSamples.load(::bundledResource))
        assertTrue(samples.kick.duration in 0.3..1.0 && samples.snare.sampleRate == 48000)
        val kit = DrumKits.build(DrumKit.ACOUSTIC, 44100, samples)
        // Resampled to the engine rate, not the synthesized fallback (which is 0.2 s long).
        assertTrue(kit.kick.size > 44100 * 0.3)
    }
}
