package com.swpp.stylemate.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.data.recommendation.OutfitGarment

/** Shared home/book layout. Photos retain their aspect ratio in the supplied photo renderer. */
@Composable
internal fun OutfitBoard(items: List<OutfitGarment>, photo: @Composable (OutfitGarment, Modifier) -> Unit,
    modifier: Modifier = Modifier) {
    val outerwear = items.firstOrNull { it.slot == "outerwear" }
    val other = items.filter { it !== outerwear }
    Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (outerwear != null) photo(outerwear, Modifier.weight(1f).fillMaxHeight())
        if (other.isNotEmpty()) {
            if (outerwear == null && other.size <= 2) {
                other.forEach { photo(it, Modifier.weight(1f).fillMaxHeight()) }
            } else Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                other.forEach { photo(it, Modifier.weight(1f).fillMaxWidth()) }
            }
        }
    }
}
