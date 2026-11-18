package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.nodes.NodeCategory
import com.example.nodes.NodeRegistry
import com.example.nodes.NodeTypeSpec
import com.example.ui.theme.*

private fun headerColorFor(category: NodeCategory): Color = when (category) {
    NodeCategory.TRIGGERS -> Color(0xFFC62828)
    NodeCategory.ACTIONS -> Color(0xFF1565C0)
    NodeCategory.CONSTRAINTS -> Color(0xFF2E7D32)
    NodeCategory.CONDITIONS -> Color(0xFF5E35B1)
    else -> Color(category.accentHex)
}

private fun iconForSubCategory(sub: String): ImageVector = when {
    sub.contains("Battery", true) || sub.contains("Power", true) -> Icons.Filled.Power
    sub.contains("Call", true) || sub.contains("SMS", true) -> Icons.Filled.Sms
    sub.contains("Messaging", true) -> Icons.Filled.AlternateEmail
    sub.contains("Connectivity", true) -> Icons.Filled.Wifi
    sub.contains("Date", true) || sub.contains("Time", true) -> Icons.Filled.Schedule
    sub.contains("Device State", true) -> Icons.Filled.ScreenLockPortrait
    sub.contains("Device Actions", true) -> Icons.Filled.TouchApp
    sub.contains("Device Settings", true) -> Icons.Filled.Settings
    sub.contains("Device Events", true) -> Icons.Filled.PhonelinkSetup
    sub.contains("Location", true) -> Icons.Filled.LocationOn
    sub.contains("Notification", true) -> Icons.Filled.Notifications
    sub.contains("Phone", true) -> Icons.Filled.Call
    sub.contains("Screen", true) -> Icons.Filled.Smartphone
    sub.contains("Sensor", true) -> Icons.Filled.Explore
    sub.contains("Volume", true) -> Icons.AutoMirrored.Filled.VolumeUp
    sub.contains("Media", true) -> Icons.Filled.PlayCircleFilled
    sub.contains("Applications", true) -> Icons.Filled.Apps
    sub.contains("Camera", true) -> Icons.Filled.CameraAlt
    sub.contains("Conditions", true) || sub.contains("Logic", true) -> Icons.AutoMirrored.Filled.CompareArrows
    sub.contains("Files", true) -> Icons.AutoMirrored.Filled.InsertDriveFile
    sub.contains("Logging", true) -> Icons.AutoMirrored.Filled.FormatListBulleted
    sub.contains("Macro", true) -> Icons.Filled.AccountTree
    sub.contains("Variable", true) -> Icons.Filled.DataObject
    sub.contains("Web", true) -> Icons.Filled.Language
    sub.contains("AI", true) -> Icons.Filled.AutoAwesome
    sub.contains("User Input", true) -> Icons.Filled.TouchApp
    else -> Icons.Filled.Widgets
}

/**
 * Full-screen MacroDroid-inspired categorized browser for all 500 Triggers,
 * 400 Actions, 200 Constraints, 500 Conditions, plus Studio Flow/Data/Custom nodes.
 */
@Composable
fun AutomationCatalogDialog(
    recentIds: List<String>,
    favouriteIds: Set<String>,
    initialCategory: NodeCategory? = NodeCategory.TRIGGERS,
    onToggleFav: (String) -> Unit,
    onSelectSpec: (NodeTypeSpec) -> Unit,
    onDismiss: () -> Unit
) {
    val customDefs by NodeRegistry.customDefinitions.collectAsStateWithLifecycle()
    val allSpecs = remember(customDefs) { NodeRegistry.allSpecs() }

    var selectedCategory by remember { mutableStateOf(initialCategory) }
    var selectedSubCategory by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }

    val primaryTabs = remember {
        listOf(
            NodeCategory.TRIGGERS,
            NodeCategory.ACTIONS,
            NodeCategory.CONSTRAINTS,
            NodeCategory.CONDITIONS
        )
    }

    val categorySpecs = remember(allSpecs, selectedCategory) {
        if (selectedCategory == null) allSpecs else allSpecs.filter { it.category == selectedCategory }
    }

    val subCategories = remember(categorySpecs) {
        categorySpecs.groupBy { it.subCategory }.map { (sub, list) -> sub to list.size }.sortedBy { it.first }
    }

    val filtered = remember(categorySpecs, selectedSubCategory, search) {
        val q = search.trim().lowercase()
        categorySpecs.filter { spec ->
            (selectedSubCategory == null || spec.subCategory == selectedSubCategory) &&
                (q.isEmpty() ||
                    spec.title.lowercase().contains(q) ||
                    spec.summary.lowercase().contains(q) ||
                    spec.subCategory.lowercase().contains(q) ||
                    spec.id.lowercase().contains(q))
        }
    }

    val headerTint = selectedCategory?.let { headerColorFor(it) } ?: Color(0xFF1E293B)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = ObsidianBg,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.90f)
                .border(1.dp, SlateBorder, RoundedCornerShape(18.dp))
                .testTag("automation_catalog_dialog")
        ) {
            Column(Modifier.fillMaxSize()) {
                // Colored MacroDroid-style header bar
                Surface(color = headerTint, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.size(48.dp).testTag("catalog_close_button")
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Close Catalog", tint = Color.White)
                            }
                            Column {
                                val titleText = when (selectedCategory) {
                                    NodeCategory.TRIGGERS -> "Add Trigger (${NodeRegistry.builtInCountFor(NodeCategory.TRIGGERS)})"
                                    NodeCategory.ACTIONS -> "Add Action (${NodeRegistry.builtInCountFor(NodeCategory.ACTIONS)})"
                                    NodeCategory.CONSTRAINTS -> "Add Constraint (${NodeRegistry.builtInCountFor(NodeCategory.CONSTRAINTS)})"
                                    NodeCategory.CONDITIONS -> "Add Condition (${NodeRegistry.builtInCountFor(NodeCategory.CONDITIONS)})"
                                    null -> "All Nodes (${allSpecs.size})"
                                    else -> "${selectedCategory?.label} (${categorySpecs.size})"
                                }
                                Text(
                                    titleText,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    "500 Triggers · 400 Actions · 200 Constraints · 500 Conditions",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }
                }

                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Top 4 MacroDroid Categories + Other Studio Categories
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(primaryTabs) { cat ->
                            val count = NodeRegistry.builtInCountFor(cat)
                            FilterChip(
                                selected = selectedCategory == cat,
                                onClick = {
                                    selectedCategory = cat
                                    selectedSubCategory = null
                                },
                                label = { Text("${cat.label} ($count)") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = headerColorFor(cat).copy(alpha = 0.28f),
                                    selectedLabelColor = Color(cat.accentHex)
                                ),
                                modifier = Modifier.testTag("catalog_tab_${cat.name.lowercase()}")
                            )
                        }
                        item {
                            FilterChip(
                                selected = selectedCategory == null,
                                onClick = {
                                    selectedCategory = null
                                    selectedSubCategory = null
                                },
                                label = { Text("All (${allSpecs.size})") },
                                modifier = Modifier.testTag("catalog_tab_all")
                            )
                        }
                        items(NodeCategory.entries.filter { it !in primaryTabs }) { cat ->
                            FilterChip(
                                selected = selectedCategory == cat,
                                onClick = {
                                    selectedCategory = cat
                                    selectedSubCategory = null
                                },
                                label = { Text(cat.label) }
                            )
                        }
                    }

                    // Search bar
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        label = { Text("Search PIN unlock, touch, mobile on/off, SMS, Wi-Fi...") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (search.isNotEmpty()) {
                                IconButton(onClick = { search = "" }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("catalog_search_input")
                    )

                    // Subcategory chips row OR category overview
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            FilterChip(
                                selected = selectedSubCategory == null,
                                onClick = { selectedSubCategory = null },
                                label = { Text("All Groups (${categorySpecs.size})") }
                            )
                        }
                        items(subCategories, key = { it.first }) { (sub, count) ->
                            FilterChip(
                                selected = selectedSubCategory == sub,
                                onClick = { selectedSubCategory = if (selectedSubCategory == sub) null else sub },
                                leadingIcon = {
                                    Icon(
                                        iconForSubCategory(sub),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                label = { Text("$sub ($count)") }
                            )
                        }
                    }

                    // If no search active and no subCategory selected, show subcategory group cards at top + items below
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth().testTag("catalog_items_list"),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        if (search.isBlank() && selectedSubCategory == null && subCategories.size > 1) {
                            items(subCategories, key = { "sub_${it.first}" }) { (sub, count) ->
                                Surface(
                                    color = SlateSurface,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 52.dp)
                                        .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
                                        .clickable { selectedSubCategory = sub }
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Box(
                                                Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(headerTint.copy(alpha = 0.25f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    iconForSubCategory(sub),
                                                    contentDescription = sub,
                                                    tint = selectedCategory?.let { Color(it.accentHex) } ?: CyberCyan,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            Column {
                                                Text(sub, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                                                Text("$count items in $sub", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                            }
                                        }
                                        Icon(Icons.Filled.ChevronRight, contentDescription = "Open $sub", tint = TextSecondary)
                                    }
                                }
                            }
                        } else {
                            items(filtered, key = { it.id }) { spec ->
                                val isFav = spec.id in favouriteIds
                                val isRecent = spec.id in recentIds
                                val accent = Color(spec.category.accentHex)
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = SlateCard),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                                        .clickable { onSelectSpec(spec) }
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(
                                                    spec.subCategory.uppercase(),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = accent
                                                )
                                                if (isRecent) {
                                                    Text("· RECENT", style = MaterialTheme.typography.labelSmall, color = AmberWarn)
                                                }
                                            }
                                            Text(
                                                spec.title + if (spec.isCustom) " (Custom)" else "",
                                                style = MaterialTheme.typography.titleSmall,
                                                color = TextPrimary
                                            )
                                            Text(spec.summary, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                        }
                                        IconButton(
                                            onClick = { onToggleFav(spec.id) },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                if (isFav) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                                contentDescription = "Toggle favourite",
                                                tint = AmberWarn
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
