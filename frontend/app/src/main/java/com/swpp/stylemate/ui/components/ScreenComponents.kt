// AI-generated with Codex, 2026-10-04, reviewed by Hyeon U Jeong
package com.swpp.stylemate.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.stylemate.ui.theme.StyleMateColors

/** Prominent tab titles and a smaller navigation bar for child pages. */
@Composable
fun PageHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null,
    onBack: (() -> Unit)? = null, enabled: Boolean = true, horizontalPadding: Dp = 20.dp,
    backDescription: String = "뒤로", subtitleContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}) {
    val childPage = onBack != null
    val titleStyle = if (childPage) MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, lineHeight = 28.sp)
        else MaterialTheme.typography.headlineSmall
    // The 48dp target adds 12dp around the 24dp arrow; align the visible arrow with the content edge.
    val startPadding = if (childPage) (horizontalPadding - 12.dp).coerceAtLeast(0.dp) else horizontalPadding
    Row(modifier.fillMaxWidth().padding(start = startPadding, end = horizontalPadding)
        .padding(vertical = if (childPage) 8.dp else 16.dp)
        .heightIn(min = if (childPage) 48.dp else 0.dp),
        verticalAlignment = if (childPage) Alignment.CenterVertically else Alignment.Top) {
        if (onBack != null) IconButton(onClick = onBack, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, backDescription)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = titleStyle, fontWeight = if (childPage) FontWeight.SemiBold else FontWeight.Bold,
                modifier = Modifier.semantics { heading() })
            if (subtitleContent != null) subtitleContent()
            else if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    primaryLabel: String?,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    navigationEnabled: Boolean = true,
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    topBar: (@Composable () -> Unit)? = null,
    subtitle: String? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        contentWindowInsets = contentWindowInsets,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (topBar != null) topBar() else PageHeader(title, subtitle = subtitle, onBack = onBack,
                enabled = navigationEnabled,
                modifier = Modifier.windowInsetsPadding(contentWindowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
                actions = {
                    if (actionLabel != null) {
                        TextButton(onClick = onAction, enabled = navigationEnabled) {
                            Text(actionLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                })
        },
        bottomBar = {
            if (primaryLabel != null) {
                PrimaryActionBar(
                    primaryLabel, primaryEnabled, onPrimary,
                    Modifier.windowInsetsPadding(contentWindowInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
                )
            }
        },
        content = content,
    )
}

@Composable
fun PrimaryActionBar(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Button(
                    onClick = onClick,
                    enabled = enabled,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    colors = ButtonDefaults.buttonColors(disabledContainerColor = StyleMateColors.Disabled,
                        disabledContentColor = StyleMateColors.DisabledInk),
                ) {
                    Text(label, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, hint: String? = null) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 20.dp, bottom = 10.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (hint != null) {
            Spacer(Modifier.size(8.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
