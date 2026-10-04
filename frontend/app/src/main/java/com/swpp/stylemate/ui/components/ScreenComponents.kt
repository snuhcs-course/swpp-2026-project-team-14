package com.swpp.stylemate.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        contentWindowInsets = contentWindowInsets,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack, enabled = navigationEnabled) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                        }
                    }
                },
                actions = {
                    if (actionLabel != null) {
                        TextButton(onClick = onAction, enabled = navigationEnabled) {
                            Text(actionLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                windowInsets = contentWindowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
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
    Box(modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Text(label, fontWeight = FontWeight.Bold)
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
