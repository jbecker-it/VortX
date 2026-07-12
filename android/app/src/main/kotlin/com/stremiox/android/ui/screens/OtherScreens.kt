package com.stremiox.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stremiox.android.BuildConfig
import com.stremiox.android.model.AuthState
import com.stremiox.android.model.Catalog
import com.stremiox.android.model.MediaType
import com.stremiox.android.model.MetaItem
import com.stremiox.android.ui.UiState
import com.stremiox.android.ui.components.Chip
import com.stremiox.android.ui.components.EmptyState
import com.stremiox.android.ui.components.ErrorState
import com.stremiox.android.ui.components.LoadingRail
import com.stremiox.android.ui.components.PosterArt
import com.stremiox.android.ui.components.PosterCard
import com.stremiox.android.ui.components.PosterRail
import com.stremiox.android.ui.theme.VortXIcons
import com.stremiox.android.ui.theme.VortXTheme
import com.stremiox.android.ui.viewmodel.DiscoverViewModel
import com.stremiox.android.ui.viewmodel.LibraryViewModel
import com.stremiox.android.ui.viewmodel.SearchViewModel

/// Discover: a [Chip] type filter (Movie/Series/...) over add-on catalog rails for that type
/// (DESIGN-SYSTEM.md §4 "Discover / Search").
@Composable
fun DiscoverScreen(viewModel: DiscoverViewModel, onItem: (MetaItem) -> Unit, modifier: Modifier = Modifier) {
    val type by viewModel.type.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = VortXTheme.spacing.edge, vertical = VortXTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(VortXTheme.spacing.xs),
        ) {
            MediaType.entries.forEach { t ->
                Chip(label = t.label, selected = t == type, onClick = { viewModel.selectType(t) })
            }
        }
        when (val s = state) {
            is UiState.Loading -> LazyColumn(
                contentPadding = PaddingValues(top = VortXTheme.spacing.sm, bottom = VortXTheme.spacing.xl),
                verticalArrangement = Arrangement.spacedBy(VortXTheme.spacing.xl),
            ) { items(List(2) { it }) { LoadingRail() } }
            is UiState.Error -> ErrorState(s.message)
            is UiState.Success -> LazyColumn(
                contentPadding = PaddingValues(bottom = VortXTheme.spacing.xl),
                verticalArrangement = Arrangement.spacedBy(VortXTheme.spacing.xl),
            ) {
                items(s.data, key = { it.id }) { catalog: Catalog ->
                    PosterRail(catalog = catalog, onItem = onItem)
                }
            }
        }
    }
}

/// Library: the user's saved titles in a poster grid (DESIGN-SYSTEM.md §4 "Library").
@Composable
fun LibraryScreen(viewModel: LibraryViewModel, onItem: (MetaItem) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is UiState.Loading -> EmptyState("Loading your library…", modifier)
        is UiState.Error -> ErrorState(s.message, modifier = modifier)
        is UiState.Success -> PosterGrid(
            items = s.data,
            onItem = onItem,
            modifier = modifier,
            emptyHint = "Titles you save appear here.",
        )
    }
}

/// Search: a query field over a poster grid of matches across every installed add-on.
@Composable
fun SearchScreen(viewModel: SearchViewModel, onItem: (MetaItem) -> Unit, modifier: Modifier = Modifier) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = VortXTheme.colors

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            leadingIcon = { Icon(VortXIcons.search, contentDescription = null) },
            placeholder = { Text("Search movies, series, channels", style = VortXTheme.type.body) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.hairline,
                cursorColor = colors.accent,
            ),
            modifier = Modifier.fillMaxWidth().padding(VortXTheme.spacing.edge),
        )
        when (val s = state) {
            is UiState.Loading -> EmptyState("Searching your add-ons…")
            is UiState.Error -> ErrorState(s.message)
            is UiState.Success -> PosterGrid(
                items = s.data,
                onItem = onItem,
                emptyHint = if (query.isBlank()) "Type to search across your add-ons." else "No matches.",
            )
        }
    }
}

/// Settings: the same controls the iOS app exposes (DESIGN-SYSTEM.md §4 "Settings / Profiles"). Most
/// values are placeholders until preferences are wired (S09); the structure is final. The Account row
/// is real (S03): it reflects the live engine [AuthState] and opens [AccountScreen] via [onAccountClick].
/// In debug builds only, one extra row opens the S02 design-system gallery for visual review — the
/// boundary is [BuildConfig.DEBUG], not a build variant, so it never ships in a release build.
@Composable
fun SettingsScreen(
    authState: AuthState,
    onAccountClick: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenGallery: (() -> Unit)? = null,
) {
    val accountValue = when (authState) {
        is AuthState.SignedIn -> authState.email ?: "Signed in"
        AuthState.SignedOut -> "Not signed in"
    }
    Column(
        modifier = modifier.fillMaxSize().padding(VortXTheme.spacing.edge),
        verticalArrangement = Arrangement.spacedBy(VortXTheme.spacing.xs),
    ) {
        SettingRow(VortXIcons.account, "Account", accountValue, onClick = onAccountClick)
        SettingRow(VortXIcons.audioOutput, "Audio output", "Auto")
        SettingRow(VortXIcons.subtitles, "Subtitle size", "Medium")
        if (BuildConfig.DEBUG && onOpenGallery != null) {
            SettingRow(VortXIcons.checkmarkCircle, "Design gallery", "Debug", onClick = onOpenGallery)
        }
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, value: String, onClick: (() -> Unit)? = null) {
    val colors = VortXTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = VortXTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(VortXTheme.spacing.md),
    ) {
        Icon(icon, contentDescription = null, tint = colors.accent)
        Text(title, style = VortXTheme.type.cardTitle, modifier = Modifier.fillMaxWidth(0.6f))
        Text(value, style = VortXTheme.type.label.copy(color = colors.textSecondary))
    }
}

@Composable
private fun PosterGrid(items: List<MetaItem>, onItem: (MetaItem) -> Unit, modifier: Modifier = Modifier, emptyHint: String) {
    if (items.isEmpty()) {
        EmptyState(emptyHint, modifier)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 112.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(VortXTheme.spacing.edge),
        horizontalArrangement = Arrangement.spacedBy(VortXTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(VortXTheme.spacing.md),
    ) {
        items(items, key = { it.id }) { item ->
            PosterCard(
                title = item.name,
                subtitle = listOfNotNull(item.year, item.type.label).joinToString(" · "),
                onClick = { onItem(item) },
                art = { PosterArt(item.poster, item.name) },
            )
        }
    }
}
