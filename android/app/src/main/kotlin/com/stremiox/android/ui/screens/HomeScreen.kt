package com.stremiox.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stremiox.android.model.Catalog
import com.stremiox.android.model.MetaItem
import com.stremiox.android.ui.UiState
import com.stremiox.android.ui.components.ErrorState
import com.stremiox.android.ui.components.LoadingRail
import com.stremiox.android.ui.components.PosterRail
import com.stremiox.android.ui.theme.VortXTheme
import com.stremiox.android.ui.viewmodel.HomeViewModel

/// Home: a featured hero (the first Continue Watching / Popular item) over the add-on catalog rails,
/// the same composition the iOS and Apple TV apps lead with (DESIGN-SYSTEM.md §4 "Home"). Driven by
/// [HomeViewModel] so loading and error are first-class states, not an empty screen.
@Composable
fun HomeScreen(viewModel: HomeViewModel, onItem: (MetaItem) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (val s = state) {
        is UiState.Loading -> LoadingColumn(modifier)
        is UiState.Error -> ErrorState(s.message, onRetry = viewModel::load, modifier = modifier)
        is UiState.Success -> HomeContent(s.data, onItem, modifier)
    }
}

@Composable
private fun HomeContent(catalogs: List<Catalog>, onItem: (MetaItem) -> Unit, modifier: Modifier) {
    val hero = catalogs.firstOrNull()?.items?.firstOrNull()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = VortXTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(VortXTheme.spacing.xl),
    ) {
        if (hero != null) {
            item { HeroHeader(hero) }
        }
        items(catalogs, key = { it.id }) { catalog ->
            // The leading Continue Watching rail carries the editorial kicker, like tvOS.
            val eyebrow = if (catalog.id == "continue") "Pick up where you left off" else null
            PosterRail(catalog = catalog, onItem = onItem, eyebrow = eyebrow)
        }
    }
}

@Composable
private fun LoadingColumn(modifier: Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = VortXTheme.spacing.xl, bottom = VortXTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(VortXTheme.spacing.xl),
    ) {
        items(List(3) { it }) { LoadingRail() }
    }
}

/// The featured-hero billboard (DESIGN-SYSTEM.md §4 "Featured hero"): a full-bleed backdrop with a
/// dual scrim (here: a vertical fade to canvas, the leading-fade half is deferred to S10's real
/// artwork/rotation work — this session only re-skins the placeholder to the token set) and the
/// bottom-left content block (eyebrow kicker + serif hero title + meta line).
@Composable
private fun HeroHeader(item: MetaItem) {
    val colors = VortXTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .background(Brush.verticalGradient(listOf(colors.surface2, colors.canvas))),
    ) {
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(VortXTheme.spacing.edge)) {
            Text(text = item.type.label.uppercase(), style = VortXTheme.type.eyebrow)
            Text(
                text = item.name,
                style = VortXTheme.type.hero,
                modifier = Modifier.padding(top = VortXTheme.spacing.xs),
            )
            item.year?.let {
                Text(
                    text = it,
                    style = VortXTheme.type.label.copy(color = colors.textSecondary),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
