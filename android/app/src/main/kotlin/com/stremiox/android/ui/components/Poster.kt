package com.stremiox.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stremiox.android.model.Catalog
import com.stremiox.android.model.MetaItem
import com.stremiox.android.ui.theme.VortXShapes
import com.stremiox.android.ui.theme.VortXTheme

/// Eyebrow kicker + section title, the shared header for every rail (DESIGN-SYSTEM.md §2 typography
/// "eyebrow"/"section title" roles) — the same two-line editorial header the tvOS `RailHeader` uses,
/// so rows read with hierarchy, not a flat list of titles.
@Composable
fun RailHeader(title: String, eyebrow: String? = null, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(start = VortXTheme.spacing.edge, bottom = VortXTheme.spacing.sm)) {
        if (eyebrow != null) {
            androidx.compose.material3.Text(text = eyebrow.uppercase(), style = VortXTheme.type.eyebrow)
        }
        androidx.compose.material3.Text(text = title, style = VortXTheme.type.sectionTitle)
    }
}

/// A titled horizontal rail of [PosterCard]s, the core building block of Home and Discover. [eyebrow]
/// adds the editorial kicker over the title (e.g. "Pick up where you left off" on Continue Watching).
@Composable
fun PosterRail(
    catalog: Catalog,
    onItem: (MetaItem) -> Unit,
    eyebrow: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        RailHeader(title = catalog.title, eyebrow = eyebrow)
        LazyRow(contentPadding = PaddingValues(horizontal = VortXTheme.spacing.edge)) {
            items(catalog.items, key = { it.id }) { item ->
                PosterCard(
                    title = item.name,
                    subtitle = listOfNotNull(item.year, item.type.label).joinToString(" · "),
                    onClick = { onItem(item) },
                    modifier = Modifier.width(124.dp).padding(end = VortXTheme.spacing.sm),
                )
            }
        }
    }
}

/// Skeleton rail shown while add-ons are still answering — the shimmer loading state (DESIGN-SYSTEM.md
/// §3 "skeleton shimmer for loading, never a bare spinner as the whole state").
@Composable
fun LoadingRail(title: String = "Loading your library", modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        RailHeader(title = title)
        LazyRow(contentPadding = PaddingValues(horizontal = VortXTheme.spacing.edge)) {
            items(List(6) { it }) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .width(124.dp)
                        .padding(end = VortXTheme.spacing.sm)
                        .aspectRatio(2f / 3f)
                        .clip(VortXShapes.card)
                        .shimmer(),
                )
            }
        }
    }
}

/// A small capsule label, e.g. the add-on name or a "TORRENT" tag on a source row — mirrors the
/// tvOS `badge`.
@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(
        text = text.uppercase(),
        style = VortXTheme.type.eyebrow.copy(color = VortXTheme.colors.textSecondary, fontWeight = FontWeight.SemiBold),
        modifier = modifier
            .clip(VortXShapes.pill)
            .background(VortXTheme.colors.surface2, VortXShapes.pill)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
