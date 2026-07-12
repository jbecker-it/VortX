package com.stremiox.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stremiox.android.model.Episode
import com.stremiox.android.model.MetaDetail
import com.stremiox.android.model.Playable
import com.stremiox.android.model.StreamGroup
import com.stremiox.android.model.StreamSource
import com.stremiox.android.ui.UiState
import com.stremiox.android.ui.components.Badge
import com.stremiox.android.ui.components.ErrorState
import com.stremiox.android.ui.components.PrimaryButton
import com.stremiox.android.ui.components.SourceRow
import com.stremiox.android.ui.theme.VortXIcons
import com.stremiox.android.ui.theme.VortXShapes
import com.stremiox.android.ui.theme.VortXTheme
import com.stremiox.android.ui.viewmodel.DetailViewModel
import com.stremiox.android.ui.viewmodel.Playback

/// Title detail, driven by [DetailViewModel]: a cinematic backdrop + the metadata row (rating · year
/// · runtime · genres), then the sources list grouped per add-on — the same information hierarchy the
/// tvOS DetailView leads with. The Watch button and the per-source rows resolve a source through the
/// repository and launch the player via [onPlay]; the resolve round-trip (streaming-server hand-off /
/// debrid unlock) is reflected as a Resolving state so the UI shows progress, not a freeze.
///
/// Re-skinned to the S02 design system: the one [PrimaryButton] Watch CTA, [SourceRow] for every
/// source, [Chip] for episode selection — no bare `MaterialTheme.colorScheme` colors remain here.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    title: String,
    onBack: () -> Unit,
    onPlay: (Playable) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metaState by viewModel.meta.collectAsStateWithLifecycle()
    val streamsState by viewModel.streams.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val selectedEpisodeId by viewModel.selectedEpisodeId.collectAsStateWithLifecycle()

    // When a source resolves, hand the Playable up to navigation and reset, so returning from the
    // player lands back on detail rather than immediately re-launching.
    LaunchedEffect(playback) {
        (playback as? Playback.Ready)?.let {
            onPlay(it.playable)
            viewModel.clearPlayback()
        }
    }

    val resolving = playback is Playback.Resolving
    val colors = VortXTheme.colors

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, style = VortXTheme.type.cardTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(VortXIcons.back, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (val m = metaState) {
            is UiState.Loading -> Box(modifier.fillMaxSize().padding(padding)) {
                Text(
                    "Loading…",
                    style = VortXTheme.type.body,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            is UiState.Error -> ErrorState(m.message, modifier = modifier.padding(padding))
            is UiState.Success -> LazyColumn(
                modifier = modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(VortXTheme.spacing.lg),
            ) {
                item { Backdrop(m.data) }
                item {
                    MetaBlock(
                        m = m.data,
                        watchEnabled = viewModel.bestSource() != null && !resolving,
                        resolving = resolving,
                        onWatch = { viewModel.bestSource()?.let(viewModel::play) },
                    )
                }
                if (m.data.videos.isNotEmpty()) {
                    item {
                        EpisodesSection(
                            episodes = m.data.videos,
                            selectedId = selectedEpisodeId,
                            onSelect = viewModel::selectEpisode,
                        )
                    }
                }
                item {
                    SourcesSection(
                        state = streamsState,
                        resolving = resolving,
                        failure = (playback as? Playback.Failed)?.message,
                        onPlay = viewModel::play,
                    )
                }
            }
        }
    }
}

/// Cinematic 16:9 backdrop (DESIGN-SYSTEM.md §4 Detail "hero banner"). With no engine artwork yet it is
/// a brand-tinted gradient with the type kicker; a real `background` image drops in behind it once
/// Coil lands (S03).
@Composable
private fun Backdrop(m: MetaDetail) {
    val colors = VortXTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Cap the backdrop's height BEFORE applying the aspect ratio -- the landscape mirror of
            // HomeScreen's HeroHeader clamp (see its doc comment). In landscape the WIDTH driving this
            // aspect ratio is the long side of the screen: on a short landscape viewport (e.g. a
            // folding phone's outer cover display rotated, ~820dp wide but only ~380dp tall available
            // below the app bar) an unclamped 16:9-of-full-width backdrop is ~460dp tall -- taller than
            // the entire viewport -- so the first frame the user sees is 100% gradient with the title,
            // Watch button, and (for a series) the episode list all scrolled below the fold. That read
            // as "the detail screen shows nothing" on the device round. Phones/tablets in portrait stay
            // under the cap, so their ratio is untouched.
            .heightIn(max = 260.dp)
            .aspectRatio(16f / 9f)
            .background(Brush.verticalGradient(listOf(colors.surface2, colors.canvas))),
    ) {
        Text(
            text = m.type.label.uppercase(),
            style = VortXTheme.type.eyebrow,
            modifier = Modifier.align(Alignment.BottomStart).padding(VortXTheme.spacing.md),
        )
    }
}

/// Title, the metadata row (rating · year · runtime · genres), the Watch button, and the synopsis —
/// the lower title band of the tvOS hero. Watch plays the best source; it is disabled until sources
/// have resolved (and while a resolve is in flight).
@Composable
private fun MetaBlock(m: MetaDetail, watchEnabled: Boolean, resolving: Boolean, onWatch: () -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = VortXTheme.spacing.edge),
        verticalArrangement = Arrangement.spacedBy(VortXTheme.spacing.xs),
    ) {
        Text(text = m.name, style = VortXTheme.type.screenTitle)
        MetaRow(m)
        PrimaryButton(
            text = if (resolving) "Starting…" else "Watch",
            onClick = onWatch,
            enabled = watchEnabled,
            loading = resolving,
            leadingIcon = if (!resolving) VortXIcons.playFill else null,
            modifier = Modifier.padding(top = VortXTheme.spacing.xs),
        )
        m.description?.let {
            Text(
                text = it,
                style = VortXTheme.type.body,
                modifier = Modifier.padding(top = VortXTheme.spacing.xs),
            )
        }
    }
}

/// rating · year · runtime · genres, the same one-line metadata strip as tvOS `metaRow`.
@Composable
private fun MetaRow(m: MetaDetail) {
    val colors = VortXTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(VortXTheme.spacing.sm)) {
        m.imdbRating?.let { rating ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    VortXIcons.starFill,
                    contentDescription = null,
                    tint = colors.accentBright,
                    modifier = Modifier.size(14.dp),
                )
                MetaText(rating)
            }
        }
        m.releaseInfo?.let { MetaText(it) }
        m.runtime?.let { MetaText(it) }
        if (m.genres.isNotEmpty()) {
            MetaText(m.genres.take(3).joinToString(" · "))
        }
    }
}

@Composable
private fun MetaText(text: String) {
    Text(text = text, style = VortXTheme.type.label.copy(color = VortXTheme.colors.textSecondary))
}

/// The episodes rail (series only): a horizontal strip of [Chip]s, the single selected look everywhere
/// (DESIGN-SYSTEM.md §3 "Chip"). Tapping one reloads the sources list scoped to that episode through
/// the engine (mirrors tvOS, where choosing an episode re-drives `meta_details.streams` for that video
/// id).
@Composable
private fun EpisodesSection(
    episodes: List<Episode>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(VortXTheme.spacing.sm),
    ) {
        Text(
            text = "Episodes",
            style = VortXTheme.type.sectionTitle,
            modifier = Modifier.padding(horizontal = VortXTheme.spacing.edge),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(VortXTheme.spacing.sm),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = VortXTheme.spacing.edge),
        ) {
            items(episodes, key = { it.id }) { episode ->
                EpisodeCard(
                    episode = episode,
                    selected = episode.id == selectedId,
                    onClick = { onSelect(episode.id) },
                )
            }
        }
    }
}

/// One episode chooser card: the season/episode label, the title, and the overview, sharing the
/// selected [Chip]-ring look (an accent border) so "current episode" reads consistently with every
/// other selected control in the app.
@Composable
private fun EpisodeCard(episode: Episode, selected: Boolean, onClick: () -> Unit) {
    val colors = VortXTheme.colors
    Column(
        modifier = Modifier
            .width(220.dp)
            .clip(VortXShapes.card)
            .background(if (selected) colors.accentSoft else colors.surface1)
            .then(
                if (selected) Modifier.border(BorderStroke(1.dp, colors.accent), VortXShapes.card) else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(VortXTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Badge(
            if (episode.season > 0) "S${episode.season} · E${episode.episode}"
            else "Episode ${episode.episode}",
        )
        Text(
            text = episode.title,
            style = VortXTheme.type.cardTitle.copy(color = if (selected) colors.accentBright else colors.textPrimary),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        episode.overview?.let {
            Text(text = it, style = VortXTheme.type.body, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/// The sources section: the per-add-on, multi-quality source list the engine fans out. Mirrors the
/// tvOS `CoreStreamList` hierarchy — a header with the source count, then the ranked [SourceRow] list.
/// [resolving] dims the rows while a resolve is in flight, and [failure] surfaces a resolve error
/// inline.
@Composable
private fun SourcesSection(
    state: UiState<List<StreamGroup>>,
    resolving: Boolean,
    failure: String?,
    onPlay: (StreamSource) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = VortXTheme.spacing.edge),
        verticalArrangement = Arrangement.spacedBy(VortXTheme.spacing.sm),
    ) {
        when (state) {
            is UiState.Loading -> Text("Finding sources…", style = VortXTheme.type.sectionTitle)
            is UiState.Error -> Text(state.message, style = VortXTheme.type.body)
            is UiState.Success -> {
                val total = state.data.sumOf { it.streams.size }
                Text(text = "Sources · $total", style = VortXTheme.type.sectionTitle)
                Text(
                    text = if (resolving) "Starting source…" else "Tap a source to play.",
                    style = VortXTheme.type.body,
                )
                failure?.let {
                    Text(text = it, style = VortXTheme.type.body.copy(color = VortXTheme.colors.danger))
                }
                state.data.forEach { group ->
                    group.streams.forEach { source ->
                        SourceRow(
                            addon = source.addon,
                            title = source.title,
                            quality = source.quality,
                            isTorrent = source.isTorrent,
                            flavorTags = listOfNotNull(source.description),
                            enabled = !resolving,
                            onClick = { onPlay(source) },
                        )
                    }
                }
            }
        }
    }
}
