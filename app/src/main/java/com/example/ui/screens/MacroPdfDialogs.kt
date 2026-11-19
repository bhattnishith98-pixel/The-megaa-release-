package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.StudioViewModel
import com.example.macros.*
import com.example.nodes.NodeCategory
import com.example.ui.theme.*
import kotlinx.coroutines.launch

private val TriggerRed = Color(0xFFC62828)
private val ActionBlue = Color(0xFF1565C0)
private val ConstraintGreen = Color(0xFF2E7D32)

/**
 * Full PDF-inspired Red/Blue/Green Visual Macro Builder Dialog:
 * - Red Card: Triggers ("WHEN the macro starts (any one of them)")
 * - Blue Card: Actions ("Actions run in order")
 * - Green Card: Constraints ("ONLY IF — ALL / ANY / ONE(XOR) / NONE(NOT) pass")
 * - Action bar: Run actions, Test, Save
 */
@Composable
fun PdfMacroBuilderDialog(
    vm: StudioViewModel,
    initialMacro: MacroRecord?,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val variables by vm.macroEngine.variables.collectAsStateWithLifecycle()

    var macroName by remember { mutableStateOf(initialMacro?.name ?: "SmartAutoMacro") }
    var macroEnabled by remember { mutableStateOf(initialMacro?.enabled ?: true) }
    var triggers by remember {
        mutableStateOf(
            initialMacro?.triggers?.ifEmpty {
                listOf(MacroTriggerRule("trigger.shake", "Shake Device", "Sensors", ""))
            } ?: listOf(MacroTriggerRule("trigger.shake", "Shake Device", "Sensors", ""))
        )
    }
    var actions by remember {
        mutableStateOf(
            initialMacro?.actionSteps?.ifEmpty {
                listOf(MacroActionStep("action.vibrate", "Vibrate", "device", "150"))
            } ?: listOf(MacroActionStep("action.vibrate", "Vibrate", "device", "150"))
        )
    }
    var constraints by remember {
        mutableStateOf(
            initialMacro?.constraints ?: listOf(
                MacroConstraintRule("constraint.screen_on", "Screen On/Off", "screen", "true")
            )
        )
    }
    var gate by remember { mutableStateOf(initialMacro?.constraintGate ?: ConstraintLogicGate.AND) }
    var catalogPickerCategory by remember { mutableStateOf<NodeCategory?>(null) }
    var testStatusLines by remember { mutableStateOf<List<String>>(emptyList()) }

    fun buildCandidateRecord(): MacroRecord {
        val code = MacroSourceBuilder.buildSourceFromBlocks(
            name = macroName,
            triggers = triggers,
            actionSteps = actions,
            constraints = constraints,
            gate = gate,
            variables = variables
        )
        val desc = "Auto: ${triggers.size} trigger(s), ${actions.size} action(s), ${constraints.size} constraint(s) [${gate.name}]"
        return MacroRecord(
            id = initialMacro?.id ?: "macro_${System.currentTimeMillis()}",
            name = macroName.ifBlank { "CustomMacro" },
            description = desc,
            sourceCode = code,
            enabled = macroEnabled,
            triggers = triggers,
            actionSteps = actions,
            constraints = constraints,
            constraintGate = gate
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = ObsidianBg,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
                .border(1.dp, SlateBorder, RoundedCornerShape(20.dp))
                .testTag("pdf_macro_builder_dialog")
        ) {
            Column(Modifier.fillMaxSize()) {
                // Header
                Surface(color = SlateSurface, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextPrimary)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (initialMacro == null) "Create Reactive Macro" else "Edit Macro Blocks",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Triggers -> Constraints -> Ordered Actions",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyberCyan
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Enabled", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            Switch(checked = macroEnabled, onCheckedChange = { macroEnabled = it })
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        OutlinedTextField(
                            value = macroName,
                            onValueChange = { macroName = it },
                            label = { Text("Macro name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("macro_builder_name_input")
                        )
                    }

                    // RED CARD: TRIGGERS
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, TriggerRed.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                                .background(TriggerRed.copy(alpha = 0.14f))
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(TriggerRed)
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Triggers", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
                                    Box(
                                        Modifier
                                            .clip(CircleShape)
                                            .background(Color.White.copy(alpha = 0.25f))
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("${triggers.size}", style = MaterialTheme.typography.labelSmall, color = Color.White)
                                    }
                                }
                                IconButton(
                                    onClick = { catalogPickerCategory = NodeCategory.TRIGGERS },
                                    modifier = Modifier.size(48.dp).testTag("add_trigger_block_button")
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = "Add Trigger", tint = Color.White)
                                }
                            }
                            if (triggers.isEmpty()) {
                                Text(
                                    "No triggers added (runs only manually from Play button).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(12.dp)
                                )
                            } else {
                                triggers.forEachIndexed { idx, trig ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text("${idx + 1}. ${trig.title}", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                                            Text("${trig.id}${if (trig.param.isNotBlank()) " · param=${trig.param}" else ""}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                        }
                                        IconButton(onClick = { triggers = triggers.filterIndexed { i, _ -> i != idx } }) {
                                            Icon(Icons.Filled.Delete, contentDescription = "Remove trigger", tint = Color.White.copy(alpha = 0.8f))
                                        }
                                    }
                                    HorizontalDivider(color = TriggerRed.copy(alpha = 0.25f))
                                }
                            }
                            Text(
                                "Triggers say WHEN the macro starts (any one of them).",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }

                    // BLUE CARD: ACTIONS
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, ActionBlue.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                                .background(ActionBlue.copy(alpha = 0.14f))
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(ActionBlue)
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Actions", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
                                    Box(
                                        Modifier
                                            .clip(CircleShape)
                                            .background(Color.White.copy(alpha = 0.25f))
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("${actions.size}", style = MaterialTheme.typography.labelSmall, color = Color.White)
                                    }
                                }
                                IconButton(
                                    onClick = { catalogPickerCategory = NodeCategory.ACTIONS },
                                    modifier = Modifier.size(48.dp).testTag("add_action_block_button")
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = "Add Action", tint = Color.White)
                                }
                            }
                            if (actions.isEmpty()) {
                                Text(
                                    "Add at least one action to execute when triggered.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(12.dp)
                                )
                            } else {
                                actions.forEachIndexed { idx, act ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text("${idx + 1}. ${act.title}", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                                            Text("${act.id} (${act.target}=${act.value})", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                        }
                                        IconButton(onClick = { actions = actions.filterIndexed { i, _ -> i != idx } }) {
                                            Icon(Icons.Filled.Delete, contentDescription = "Remove action", tint = Color.White.copy(alpha = 0.8f))
                                        }
                                    }
                                    HorizontalDivider(color = ActionBlue.copy(alpha = 0.25f))
                                }
                            }
                            Text(
                                "Actions run in order.",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }

                    // GREEN CARD: CONSTRAINTS + AND / OR / XOR / NOT LOGIC GATE
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, ConstraintGreen.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                                .background(ConstraintGreen.copy(alpha = 0.14f))
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(ConstraintGreen)
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Constraints", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
                                    Box(
                                        Modifier
                                            .clip(CircleShape)
                                            .background(Color.White.copy(alpha = 0.25f))
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("${constraints.size}", style = MaterialTheme.typography.labelSmall, color = Color.White)
                                    }
                                }
                                IconButton(
                                    onClick = { catalogPickerCategory = NodeCategory.CONSTRAINTS },
                                    modifier = Modifier.size(48.dp).testTag("add_constraint_block_button")
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = "Add Constraint", tint = Color.White)
                                }
                            }
                            LazyRow(
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(ConstraintLogicGate.entries) { g ->
                                    FilterChip(
                                        selected = gate == g,
                                        onClick = { gate = g },
                                        label = { Text(g.label) }
                                    )
                                }
                            }
                            if (constraints.isEmpty()) {
                                Text(
                                    "No constraints — runs unconditionally whenever trigger fires.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            } else {
                                constraints.forEachIndexed { idx, con ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text("${idx + 1}. ${con.title}", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                                            Text("${con.id} (${con.param} == ${con.expected})", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                        }
                                        IconButton(onClick = { constraints = constraints.filterIndexed { i, _ -> i != idx } }) {
                                            Icon(Icons.Filled.Delete, contentDescription = "Remove constraint", tint = Color.White.copy(alpha = 0.8f))
                                        }
                                    }
                                    HorizontalDivider(color = ConstraintGreen.copy(alpha = 0.25f))
                                }
                            }
                            Text(
                                "Constraints say ONLY IF — ${gate.description}.",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }

                    if (testStatusLines.isNotEmpty()) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                                modifier = Modifier.fillMaxWidth().border(1.dp, EmeraldRun.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    testStatusLines.forEach { line ->
                                        Text(line, style = MaterialTheme.typography.labelSmall, color = EmeraldRun)
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom PDF Action Bar: Run actions | Test | Save
                Surface(color = SlateSurface, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                val candidate = buildCandidateRecord()
                                scope.launch {
                                    val res = vm.macroEngine.runMacro(candidate, ignoreConstraints = true)
                                    testStatusLines = res.logs.map { it.message } +
                                        if (res.success) "Macro actions finished (${res.durationMs}ms)" else "Error: ${res.error}"
                                }
                            },
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("macro_builder_run_actions"),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonViolet, contentColor = Color.White)
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Run actions")
                        }
                        OutlinedButton(
                            onClick = {
                                val candidate = buildCandidateRecord()
                                scope.launch {
                                    val res = vm.macroEngine.runMacro(candidate, ignoreConstraints = false)
                                    testStatusLines = res.logs.map { it.message } +
                                        if (res.success) "Test finished (${res.instructionsExecuted} ops)" else "Test error: ${res.error}"
                                }
                            },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("macro_builder_test")
                        ) { Text("Test") }
                        Button(
                            onClick = {
                                val rec = buildCandidateRecord()
                                vm.macroEngine.upsertMacro(rec)
                                vm.setBanner("Saved reactive macro '${rec.name}'.")
                                onDismiss()
                            },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("macro_builder_save"),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldRun, contentColor = ObsidianBg)
                        ) { Text("Save") }
                    }
                }
            }
        }
    }

    catalogPickerCategory?.let { cat ->
        val recent by vm.recentNodeIds.collectAsStateWithLifecycle()
        val favs by vm.favouriteNodeIds.collectAsStateWithLifecycle()
        AutomationCatalogDialog(
            recentIds = recent,
            favouriteIds = favs,
            initialCategory = cat,
            onToggleFav = { vm.toggleFavouriteNode(it) },
            onSelectSpec = { spec ->
                when (cat) {
                    NodeCategory.TRIGGERS -> {
                        val p = spec.params.firstOrNull()?.defaultValue ?: ""
                        triggers = triggers + MacroTriggerRule(spec.id, spec.title, spec.subCategory, p)
                    }
                    NodeCategory.ACTIONS -> {
                        val target = spec.params.getOrNull(0)?.defaultValue ?: "device"
                        val v = spec.params.getOrNull(1)?.defaultValue ?: "true"
                        actions = actions + MacroActionStep(spec.id, spec.title, target, v)
                    }
                    NodeCategory.CONSTRAINTS, NodeCategory.CONDITIONS -> {
                        val param = spec.params.getOrNull(0)?.defaultValue ?: "state"
                        val exp = spec.params.getOrNull(1)?.defaultValue ?: "true"
                        constraints = constraints + MacroConstraintRule(spec.id, spec.title, param, exp)
                    }
                    else -> Unit
                }
                vm.recordNodeUsed(spec.id)
                catalogPickerCategory = null
            },
            onDismiss = { catalogPickerCategory = null }
        )
    }
}

/**
 * PDF Page 41 Global Variables Manager Dialog:
 * Variable name, Variable Type (Boolean, Integer, Decimal, String, Array, Dictionary),
 * Comment (optional), and "Make variable secure" (cleared when shared/exported).
 */
@Composable
fun VariablesManagerDialog(
    vm: StudioViewModel,
    onDismiss: () -> Unit
) {
    val variables by vm.macroEngine.variables.collectAsStateWithLifecycle()
    var varName by remember { mutableStateOf("") }
    var varType by remember { mutableStateOf(StudioVarType.BOOLEAN) }
    var varValue by remember { mutableStateOf("true") }
    var varComment by remember { mutableStateOf("") }
    var varSecure by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = ObsidianBg,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.86f)
                .border(1.dp, SlateBorder, RoundedCornerShape(18.dp))
                .testTag("variables_manager_dialog")
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Create New Variable", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                        Text("Typed global state accessible across all StudioLang macros", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Close Variables", tint = TextPrimary)
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateCard),
                    modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(14.dp))
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = varName,
                            onValueChange = { varName = it },
                            label = { Text("Enter variable name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("var_name_input")
                        )
                        Text("Variable Type", style = MaterialTheme.typography.labelMedium, color = CyberCyan)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(StudioVarType.entries) { t ->
                                FilterChip(
                                    selected = varType == t,
                                    onClick = {
                                        varType = t
                                        varValue = when (t) {
                                            StudioVarType.BOOLEAN -> "true"
                                            StudioVarType.INTEGER -> "0"
                                            StudioVarType.DECIMAL -> "0.0"
                                            StudioVarType.ARRAY -> "[]"
                                            StudioVarType.DICTIONARY -> "{}"
                                            StudioVarType.STRING -> ""
                                        }
                                    },
                                    label = { Text(t.label) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = varValue,
                            onValueChange = { varValue = it },
                            label = { Text("Initial value (${varType.label})") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("var_value_input")
                        )
                        OutlinedTextField(
                            value = varComment,
                            onValueChange = { varComment = it },
                            label = { Text("Comment (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = varSecure,
                                onCheckedChange = { varSecure = it },
                                modifier = Modifier.testTag("var_secure_checkbox")
                            )
                            Text("Make variable secure", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        }
                        Text(
                            "A variable marked as secure will have its contents cleared when macro(s) are shared/exported or uploaded.",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {
                                varName = ""
                                varComment = ""
                                varSecure = false
                            }) { Text("CLEAR", color = CyberCyan) }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                enabled = varName.isNotBlank(),
                                onClick = {
                                    vm.macroEngine.upsertVariable(
                                        StudioVariable(varName.trim(), varType, varValue, varComment.trim(), varSecure)
                                    )
                                    vm.setBanner("Saved variable '${varName.trim()}' (${varType.label}).")
                                    varName = ""
                                    varComment = ""
                                    varSecure = false
                                },
                                modifier = Modifier.testTag("var_ok_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = ObsidianBg)
                            ) { Text("OK") }
                        }
                    }
                }

                Text("Configured Variables (${variables.size})", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                if (variables.isEmpty()) {
                    Text("No Variables Configured", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                } else {
                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(variables, key = { it.name }) { v ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                                modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(v.name, style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                                            Text(v.type.label, style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                                            if (v.isSecure) {
                                                Text("SECURE", style = MaterialTheme.typography.labelSmall, color = AmberWarn)
                                            }
                                        }
                                        Text(
                                            if (v.isSecure) "Value: •••••••• (redacted on export)" else "Value: ${v.value}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                        if (v.comment.isNotBlank()) {
                                            Text(v.comment, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                        }
                                    }
                                    IconButton(onClick = { vm.macroEngine.deleteVariable(v.name) }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Delete variable", tint = RoseError)
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
