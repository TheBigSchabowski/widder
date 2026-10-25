package widder.android

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import widder.core.Action
import widder.core.ActionKind
import widder.core.Deck
import widder.core.DeckKind
import widder.ui.Palette
import widder.ui.WidderTheme
import widder.ui.kindColor

/**
 * Configuration screen shown when the user places a Widder widget on their home screen
 * or reconfigures an existing widget.
 *
 * Offers one-tap selection for:
 * - A-Drone ohne Quinte (starts Practice and plays the A drone without fifth immediately)
 * - Üben (opens the Practice screen with tuner, drone, drums, looper, vibrato)
 * - Akkordfolgen lernen (starts the chord progression deck or opens the Multiple Choice list)
 * - Every individual learning deck (Multiple Choice / Gehör & Harmonielehre and Flash Cards)
 */
class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Default result is CANCELED in case the user backs out without picking an option.
        setResult(Activity.RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val snapshot = WidderWidgetProvider.loadSnapshot(applicationContext)
        val current = WidderWidgetProvider.loadAction(applicationContext, appWidgetId)

        setContent {
            WidderTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WidgetConfigScreen(
                        snapshot = snapshot,
                        current = current,
                        onSelect = ::confirmSelection,
                        onCancel = ::finish,
                    )
                }
            }
        }
    }

    private fun confirmSelection(action: Action) {
        WidderWidgetProvider.saveAction(applicationContext, appWidgetId, action)
        WidderWidgetProvider.updateOne(applicationContext, appWidgetId)
        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(Activity.RESULT_OK, resultValue)
        finish()
    }
}

@Composable
private fun WidgetConfigScreen(
    snapshot: WidderWidgetProvider.Companion.Snapshot,
    current: Action,
    onSelect: (Action) -> Unit,
    onCancel: () -> Unit,
) {
    val collection = snapshot.collection
    val progress = snapshot.progress
    val chordDecks = collection.decks.filter { it.kind == DeckKind.CHORDS }.sortedBy { it.name.lowercase() }
    val cardDecks = collection.decks.filter { it.kind == DeckKind.CARDS }.sortedBy { it.name.lowercase() }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxSize().padding(top = 36.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Widget einrichten",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Was soll beim Antippen auf dem Startbildschirm direkt starten?",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onCancel) {
                    Text("Abbrechen")
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    SectionHeader("Üben & Drone")
                }
                item {
                    OptionCard(
                        title = "A-Drone ohne Quinte",
                        subtitle = "Startet sofort den Grundton A ohne Quinte (nochmal tippen stoppt)",
                        badge = "▶ Drone",
                        color = Palette.practice,
                        selected = current.kind == ActionKind.DRONE_A_NO_FIFTH,
                        onClick = { onSelect(Action(ActionKind.DRONE_A_NO_FIFTH)) },
                    )
                }
                item {
                    OptionCard(
                        title = "Üben öffnen",
                        subtitle = "Stimmgerät, Drone, Drums, Looper und Vibrato-Trainer",
                        badge = "Üben",
                        color = Palette.practice,
                        selected = current.kind == ActionKind.OPEN_PRACTICE,
                        onClick = { onSelect(Action(ActionKind.OPEN_PRACTICE)) },
                    )
                }

                item {
                    Spacer(Modifier.height(6.dp))
                    SectionHeader("Akkordfolgen & Gehör (Multiple Choice)")
                }
                // Highlight chord progressions deck if present, plus all chord/interval training decks.
                items(chordDecks, key = { "chord-" + it.id }) { deck ->
                    DeckOptionCard(
                        deck = deck,
                        due = deck.counts(collection.cards, progress).total,
                        totalCards = collection.cards.count { it.deckId == deck.id },
                        selected = current.kind == ActionKind.STUDY && current.deckId == deck.id,
                        onClick = { onSelect(Action(ActionKind.STUDY, deck.id)) },
                    )
                }
                item {
                    OptionCard(
                        title = "Alle Multiple-Choice-Stapel",
                        subtitle = "Öffnet die Übersicht aller Akkordfolgen- und Gehör-Stapel",
                        badge = "${chordDecks.size} Stapel",
                        color = Palette.chords,
                        selected = current.kind == ActionKind.OPEN_CHORDS,
                        onClick = { onSelect(Action(ActionKind.OPEN_CHORDS)) },
                    )
                }

                item {
                    Spacer(Modifier.height(6.dp))
                    SectionHeader("Lerndecks (Flash Cards)")
                }
                items(cardDecks, key = { "card-" + it.id }) { deck ->
                    DeckOptionCard(
                        deck = deck,
                        due = deck.counts(collection.cards, progress).total,
                        totalCards = collection.cards.count { it.deckId == deck.id },
                        selected = current.kind == ActionKind.STUDY && current.deckId == deck.id,
                        onClick = { onSelect(Action(ActionKind.STUDY, deck.id)) },
                    )
                }
                item {
                    OptionCard(
                        title = "Alle Flash-Cards-Stapel",
                        subtitle = "Öffnet die Übersicht aller Flash-Cards-Stapel",
                        badge = "${cardDecks.size} Stapel",
                        color = Palette.cards,
                        selected = current.kind == ActionKind.OPEN_CARDS,
                        onClick = { onSelect(Action(ActionKind.OPEN_CARDS)) },
                    )
                }

                item {
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DeckOptionCard(
    deck: Deck,
    due: Int,
    totalCards: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = kindColor(deck.kind)
    val kindLabel = if (deck.kind == DeckKind.CARDS) "Flash Cards · $totalCards Karten" else "Multiple Choice · $totalCards Übungen"
    val badge = if (due > 0) "$due fällig" else "Direkt starten"
    OptionCard(
        title = deck.name,
        subtitle = kindLabel,
        badge = badge,
        color = color,
        selected = selected,
        onClick = onClick,
    )
}

@Composable
private fun OptionCard(
    title: String,
    subtitle: String,
    badge: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .then(
                if (selected) Modifier.border(2.dp, color, RoundedCornerShape(18.dp))
                else Modifier,
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(color.copy(alpha = 0.16f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
