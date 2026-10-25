package widder.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import widder.core.Action
import widder.core.ActionKind
import widder.core.Collection
import widder.core.DeckKind
import widder.core.DirectoryStore
import widder.core.Progress
import widder.core.Store
import widder.ui.DemoContent
import widder.ui.StarterKits

/**
 * Home-screen widget that launches a configured Widder action with a single tap:
 * e.g., an A drone without the fifth ("Drone auf A (ohne Quinte)"), the Practice screen
 * ("Üben"), chord progression ear-training ("Erste Akkordfolgen"), or any flash-card /
 * multiple-choice deck ("Lerndecks").
 *
 * Each widget instance stores its chosen [Action] in SharedPreferences keyed by `appWidgetId`.
 * When decks, learning progress, or practice state change inside the app, [updateAll] refreshes
 * all placed widgets so due card counts and active drone status stay up to date.
 */
class WidderWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val snapshot = loadSnapshot(context)
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId, snapshot)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val prefs = prefs(context).edit()
        for (id in appWidgetIds) {
            prefs.remove(keyKind(id))
            prefs.remove(keyDeck(id))
        }
        prefs.apply()
    }

    companion object {
        const val ACTION_WIDGET = "widder.WIDGET_ACTION"
        const val EXTRA_ACTION_KIND = "widder.action_kind"
        const val EXTRA_DECK_ID = "widder.deck_id"

        private const val PREFS_NAME = "widder_widgets"
        private fun keyKind(id: Int) = "kind_$id"
        private fun keyDeck(id: Int) = "deck_$id"

        private const val COLOR_CARDS = 0xFFF2A541.toInt()
        private const val COLOR_CHORDS = 0xFF3FB8A6.toInt()
        private const val COLOR_PRACTICE = 0xFF9B6DD6.toInt()
        private const val COLOR_ACTIVE = 0xFF46A758.toInt()

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        /** Persists the [action] chosen for [appWidgetId] in [WidgetConfigActivity]. */
        fun saveAction(context: Context, appWidgetId: Int, action: Action) {
            prefs(context).edit()
                .putString(keyKind(appWidgetId), action.kind.name)
                .putString(keyDeck(appWidgetId), action.deckId)
                .apply()
        }

        /** Reads the [Action] configured for [appWidgetId]; defaults to A drone without fifth. */
        fun loadAction(context: Context, appWidgetId: Int): Action {
            val p = prefs(context)
            val kindName = p.getString(keyKind(appWidgetId), null)
            val kind = kindName?.let { runCatching { ActionKind.valueOf(it) }.getOrNull() } ?: ActionKind.DRONE_A_NO_FIFTH
            val deckId = p.getString(keyDeck(appWidgetId), null)
            return Action(kind, deckId)
        }

        /** Refreshes all placed Widder widgets on the home screen. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, WidderWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val snapshot = loadSnapshot(context)
            for (id in ids) {
                updateWidget(context, manager, id, snapshot)
            }
        }

        /** Refreshes a single widget instance right after configuration. */
        fun updateOne(context: Context, appWidgetId: Int) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            updateWidget(context, manager, appWidgetId, loadSnapshot(context))
        }

        /**
         * Snapshot of the user's collection, learning progress, and live practice state.
         * Uses the running ViewModel when available, otherwise reads directly from disk
         * (and initializes the standard starter kits if the app was freshly installed).
         */
        internal data class Snapshot(
            val collection: Collection,
            val progress: Progress,
            val droneAWithoutFifthActive: Boolean,
            val practiceActive: Boolean,
        )

        internal fun loadSnapshot(context: Context): Snapshot {
            val liveVm = MainActivity.peekViewModel()
            if (liveVm != null) {
                val droneANoFifth = liveVm.droneOn && liveVm.practice.root == 9 && !liveVm.practice.fifth
                val anyPractice = liveVm.droneOn || liveVm.drumsOn
                return Snapshot(liveVm.collection, liveVm.progress, droneANoFifth, anyPractice)
            }
            val store = Store(DirectoryStore(context.filesDir))
            val collection = store.loadCollection() ?: ensureInitialCollection(store)
            val progress = store.loadProgress()
            return Snapshot(collection, progress, droneAWithoutFifthActive = false, practiceActive = false)
        }

        /**
         * Ensures that even if a user places a widget right after installing Widder (before
         * opening the app for the first time), the demo decks and starter kits exist on disk.
         */
        internal fun ensureInitialCollection(store: Store): Collection {
            var c = DemoContent.create(store)
            for (id in StarterKits.STANDARD) {
                val (deck, cards) = StarterKits.kit(id)?.build(store) ?: continue
                if (c.decks.none { it.id == id }) {
                    c = c.copy(decks = c.decks + deck, cards = c.cards + cards)
                }
            }
            val initialized = c.copy(settings = c.settings.copy(standardKits = StarterKits.STANDARD_VERSION))
            store.saveCollection(initialized)
            return initialized
        }

        private data class Presentation(
            val title: String,
            val subtitle: String,
            val badge: String?,
            val accentColor: Int,
        )

        private fun present(action: Action, snapshot: Snapshot): Presentation {
            val c = snapshot.collection
            val p = snapshot.progress
            return when (action.kind) {
                ActionKind.DRONE_A_NO_FIFTH -> {
                    val active = snapshot.droneAWithoutFifthActive
                    Presentation(
                        title = "A-Drone",
                        subtitle = if (active) "Läuft · Antippen für Stopp" else "Ohne Quinte · Direkt starten",
                        badge = if (active) "■" else "▶",
                        accentColor = if (active) COLOR_ACTIVE else COLOR_PRACTICE,
                    )
                }
                ActionKind.OPEN_PRACTICE -> {
                    val active = snapshot.practiceActive
                    Presentation(
                        title = "Üben",
                        subtitle = if (active) "Aktiv · Drone, Drums & Looper" else "Drone, Drums, Looper & Stimmgerät",
                        badge = "›",
                        accentColor = if (active) COLOR_ACTIVE else COLOR_PRACTICE,
                    )
                }
                ActionKind.OPEN_CHORDS -> {
                    val decks = c.decks.filter { it.kind == DeckKind.CHORDS }
                    val due = decks.sumOf { it.counts(c.cards, p).total }
                    Presentation(
                        title = "Akkordfolgen & Gehör",
                        subtitle = if (due > 0) "$due fällig in ${decks.size} Stapeln" else "${decks.size} Stapel · Multiple Choice",
                        badge = if (due > 0) "$due" else "›",
                        accentColor = COLOR_CHORDS,
                    )
                }
                ActionKind.OPEN_CARDS -> {
                    val decks = c.decks.filter { it.kind == DeckKind.CARDS }
                    val due = decks.sumOf { it.counts(c.cards, p).total }
                    Presentation(
                        title = "Flash Cards",
                        subtitle = if (due > 0) "$due fällig in ${decks.size} Stapeln" else "${decks.size} Stapel · Lernen",
                        badge = if (due > 0) "$due" else "›",
                        accentColor = COLOR_CARDS,
                    )
                }
                ActionKind.STUDY, ActionKind.BROWSE -> {
                    val deck = action.deckId?.let { id -> c.decks.firstOrNull { it.id == id } }
                    if (deck == null) {
                        Presentation(
                            title = "Lerndeck wählen",
                            subtitle = "Stapel nicht gefunden",
                            badge = "?",
                            accentColor = COLOR_CARDS,
                        )
                    } else {
                        val counts = deck.counts(c.cards, p)
                        val color = if (deck.kind == DeckKind.CARDS) COLOR_CARDS else COLOR_CHORDS
                        val modeLabel = when {
                            action.kind == ActionKind.BROWSE -> "Stapel öffnen"
                            deck.kind == DeckKind.CARDS -> "Flash Cards"
                            else -> "Trainieren"
                        }
                        val sub = if (counts.total > 0) {
                            "${counts.total} fällig · $modeLabel"
                        } else {
                            val totalCards = c.cards.count { it.deckId == deck.id }
                            "Alles gelernt ($totalCards) · $modeLabel"
                        }
                        Presentation(
                            title = deck.name,
                            subtitle = sub,
                            badge = if (counts.total > 0) "${counts.total}" else "✓",
                            accentColor = color,
                        )
                    }
                }
                else -> Presentation(
                    title = action.kind.label,
                    subtitle = "Widder öffnen",
                    badge = "›",
                    accentColor = COLOR_CARDS,
                )
            }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, appWidgetId: Int, snapshot: Snapshot) {
            val action = loadAction(context, appWidgetId)
            val info = present(action, snapshot)
            val views = RemoteViews(context.packageName, R.layout.widder_widget)

            views.setTextViewText(R.id.widget_title, info.title)
            views.setTextViewText(R.id.widget_subtitle, info.subtitle)
            views.setInt(R.id.widget_dot, "setColorFilter", info.accentColor)

            if (info.badge != null) {
                views.setViewVisibility(R.id.widget_badge, View.VISIBLE)
                views.setTextViewText(R.id.widget_badge, info.badge)
                views.setTextColor(R.id.widget_badge, info.accentColor)
            } else {
                views.setViewVisibility(R.id.widget_badge, View.GONE)
            }

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                this.action = ACTION_WIDGET
                putExtra(EXTRA_ACTION_KIND, action.kind.name)
                if (action.deckId != null) {
                    putExtra(EXTRA_DECK_ID, action.deckId)
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            manager.updateAppWidget(appWidgetId, views)
        }
    }
}
