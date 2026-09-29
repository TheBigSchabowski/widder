package widder.core

import kotlin.random.Random

/**
 * Bildbelohnung, brought over from the AnkiDroid fork: the start screen's background image starts
 * blurred and one tile gets sharp for every [RewardSettings.secondsPerTile] seconds of learning.
 *
 * Unlike the fork, the tiles are laid over the visible part of the screen rather than over the
 * whole picture, so every tile that is earned can be seen and no cropping step is needed.
 * Only time spent answering cards and exercises counts.
 */
object Reward {
    fun tileCount(settings: RewardSettings): Int = settings.columns * settings.rows

    /** Progress as of [now]: a new learning day starts from zero when daily reset is on. */
    fun current(progress: RewardProgress, settings: RewardSettings, now: Long = currentTimeMillis()): RewardProgress {
        val day = Store.learningDay(now)
        var p = progress
        if (settings.resetDaily && p.day != day) p = p.copy(day = day, seconds = 0)
        if (p.day.isEmpty()) p = p.copy(day = day)
        val count = tileCount(settings)
        if (p.order.size != count || p.order.toSet() != (0 until count).toSet()) {
            p = p.copy(order = (0 until count).shuffled(Random(now)))
        }
        return p
    }

    fun addSeconds(progress: RewardProgress, settings: RewardSettings, seconds: Int, now: Long = currentTimeMillis()): RewardProgress {
        val p = current(progress, settings, now)
        return p.copy(seconds = (p.seconds + seconds).coerceAtMost(tileCount(settings) * settings.secondsPerTile))
    }

    fun unlocked(progress: RewardProgress, settings: RewardSettings): Int =
        (progress.seconds / settings.secondsPerTile.coerceAtLeast(1)).coerceAtMost(tileCount(settings))

    /** The tiles (by index, row by row) that are sharp. */
    fun sharpTiles(progress: RewardProgress, settings: RewardSettings): Set<Int> =
        progress.order.take(unlocked(progress, settings)).toSet()

    /** Seconds of learning until the next tile, or null when the picture is complete. */
    fun secondsToNext(progress: RewardProgress, settings: RewardSettings): Int? {
        if (unlocked(progress, settings) >= tileCount(settings)) return null
        return settings.secondsPerTile - progress.seconds % settings.secondsPerTile
    }

    fun reset(progress: RewardProgress): RewardProgress = progress.copy(seconds = 0, order = emptyList())
}
