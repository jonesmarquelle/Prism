package com.prism.music.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.data.db.ImportMatchEntity
import com.prism.music.data.imports.TrackMatcher
import com.prism.music.data.imports.TrackToMatch
import com.prism.music.data.model.Song
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.tr
import com.prism.music.ui.components.EmptyState
import com.prism.music.ui.components.PrismProgressBar
import com.prism.music.ui.components.PrismTextField
import com.prism.music.ui.components.SubPage
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.launch

/*
 * This fork's additions to "Needs a look" (Bring your history): a search on each card, for when the right song isn't
 * among the three Prism found (they're YouTube's first results, so remixes and remasters can crowd the song out), and
 * "Passed over": the songs the listener said "None of these" to, which upstream never shows again.
 */

/** Search on a "Needs a look" card: YouTube Music's songs for what the listener types, each to play or pick. */
@Composable
internal fun ReviewSearch(m: ImportMatchEntity) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var open by rememberSaveable(m.songKey, "search") { mutableStateOf(false) }
    if (!open) {
        Item(tr(R.string.imp_search_for_it), null, onClick = { open = true }, icon = PrismIcons.Search)
        return
    }
    var query by rememberSaveable(m.songKey, "query") { mutableStateOf(TrackMatcher.queryFor(TrackToMatch(m.title, m.artist))) }
    var results by remember(m.songKey) { mutableStateOf<List<Song>?>(null) }
    var searching by remember(m.songKey) { mutableStateOf(false) }
    var failed by remember(m.songKey) { mutableStateOf(false) }
    fun search() {
        val q = query.trim()
        if (q.isEmpty() || searching) return
        searching = true
        failed = false
        scope.launch {
            val found = runCatching { c.imports.searchSongs(q) }
            results = found.getOrNull()?.filter { !it.isEpisode }?.distinctBy { it.id }?.take(RESULTS)
            failed = found.isFailure
            searching = false
        }
    }
    PrismTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        placeholder = { Text(tr(R.string.imp_search_hint)) },
        leadingIcon = { Icon(PrismIcons.Search, null) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { search() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
    // Opened: search at once with the song's name, as matching did, so a tap is often enough.
    androidx.compose.runtime.LaunchedEffect(m.songKey) { if (results == null) search() }
    when {
        searching -> PrismProgressBar(null, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        failed -> Note(tr(R.string.imp_search_failed))
        results?.isEmpty() == true -> Note(tr(R.string.imp_search_nothing))
    }
    results.orEmpty().forEach { s ->
        CandidateRow(s, onPlay = { c.player.playQueue(listOf(s), 0, QueueSource(QueueKind.SINGLE, s.title)) }) {
            scope.launch { c.imports.fix(m.songKey, s) }
        }
    }
}

private const val RESULTS = 8

/** "Passed over": the songs said "None of these" to, each a card to pick from (or search) after all. */
@Composable
fun ImportPassedScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val passed by c.imports.passedOver.collectAsState(null)
    SubPage(tr(R.string.imp_passed_over), bottomPadding, subtitle = tr(R.string.imp_passed_over_subtitle), horizontalPadding = 16.dp, spacing = 18.dp) {
        val list = passed
        if (list == null) { item { com.prism.music.ui.components.LoadingState() }; return@SubPage }
        if (list.isEmpty()) { item { EmptyState(PrismIcons.Check, tr(R.string.imp_passed_over_empty), tr(R.string.imp_passed_over_empty_body)) }; return@SubPage }
        list.forEach { m -> item(key = m.songKey) { ReviewCard(m, passedOver = true) } }
    }
}
