package com.swpp.stylemate.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable
fun OutfitFeedbackButtons(feedback: String?, enabled: Boolean, onLike: () -> Unit, onDislike: () -> Unit,
    modifier: Modifier = Modifier, leading: (@Composable (Modifier) -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        leading?.invoke(Modifier.weight(1f))
        FeedbackButton("좋아요", feedback == "liked", enabled, onLike, Modifier.weight(1f))
        FeedbackButton("별로예요", feedback == "disliked", enabled, onDislike, Modifier.weight(1f), negative = true)
    }
}

@Composable
private fun FeedbackButton(label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit,
    modifier: Modifier, negative: Boolean = false) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        modifier = modifier.heightIn(min = 48.dp).semantics {
            selected = active
            stateDescription = if (active) "선택됨" else "선택 안 됨"
        }) {
        Icon(if (active) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, null,
            Modifier.size(16.dp).rotate(if (negative) 180f else 0f))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
fun OutfitSaveButton(saved: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    saving: Boolean = false, failed: Boolean = false, star: Boolean = false) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.outlinedButtonColors(
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        modifier = modifier.heightIn(min = 48.dp).semantics {
            contentDescription = if (saved) "코디북에 저장됨" else "코디북에 저장"
            stateDescription = if (saved) "저장됨" else if (saving) "저장 중" else "저장 안 됨"
            selected = saved
        }) {
        if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        else if (star && !saved) Icon(androidx.compose.ui.res.painterResource(com.swpp.stylemate.R.drawable.ic_star_outline), null, Modifier.size(18.dp))
        else Icon(if (saved) Icons.Filled.Star else Icons.Filled.Add, null, Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(when { saved -> "저장됨"; saving -> "저장 중"; failed -> "재저장"; else -> "저장" },
            style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
