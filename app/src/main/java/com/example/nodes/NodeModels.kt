package com.example.nodes

import com.example.lang.StudioCapability

enum class NodeCategory(val label: String, val accentHex: Long) {
    TRIGGERS("Triggers", 0xFFEF5350),
    ACTIONS("Actions", 0xFF42A5F5),
    CONSTRAINTS("Constraints", 0xFF4ADE80),
    CONDITIONS("Conditions", 0xFFA78BFA),
    FLOW("Logic / Flow", 0xFFA855F7),
    DATA("Data", 0xFFFBBF24),
    FILES("Files", 0xFFFB923C),
    PERMISSIONS("Permissions", 0xFF60A5FA),
    CREDENTIALS("Credentials", 0xFFF472B6),
    AGENT("Agent / AI", 0xFFC084FC),
    BLOCKS("Blocks", 0xFF2DD4BF),
    APP_BUILDING("App Builder", 0xFF818CF8),
    DEBUG("Debug", 0xFF94A3B8)
}

enum class PortDirection { IN, OUT }

enum class PortType(val label: String, val colorHex: Long) {
    FLOW("flow", 0xFFF8FAFC),
    NUMBER("number", 0xFF22D3EE),
    TEXT("text", 0xFFFBBF24),
    BOOL("bool", 0xFF34D399),
    LIST("list", 0xFFA855F7),
    MAP("map", 0xFFF472B6),
    TIME("time", 0xFF38BDF8),
    DURATION("duration", 0xFFC084FC),
    FILE("file", 0xFFFB923C),
    ANY("any", 0xFF94A3B8)
}

data class NodePortSpec(
    val id: String,
    val name: String,
    val direction: PortDirection,
    val type: PortType
)

data class NodeParamSpec(
    val key: String,
    val label: String,
    val type: PortType,
    val defaultValue: String,
    val options: List<String> = emptyList()
)

data class NodeTypeSpec(
    val id: String,
    val title: String,
    val category: NodeCategory,
    val summary: String,
    val inputs: List<NodePortSpec>,
    val outputs: List<NodePortSpec>,
    val params: List<NodeParamSpec> = emptyList(),
    val requiredCapabilities: Set<StudioCapability> = emptySet(),
    val minApiLevel: Int = 24,
    val docSyntax: String = "",
    val docExample: String = "",
    val isCustom: Boolean = false,
    val subCategory: String = "General"
)

data class GraphNode(
    val id: String,
    val typeId: String,
    val label: String,
    val x: Float,
    val y: Float,
    val params: Map<String, String> = emptyMap(),
    val colorTagHex: Long? = null,
    val collapsed: Boolean = false,
    val hasBreakpoint: Boolean = false
)

data class GraphWire(
    val id: String,
    val fromNodeId: String,
    val fromPortId: String,
    val toNodeId: String,
    val toPortId: String,
    val autoConverted: Boolean = false
)

data class CommentBox(
    val id: String,
    val title: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val colorHex: Long = 0xFF1E293B
)

data class NodeGroup(
    val id: String,
    val title: String,
    val nodeIds: List<String>,
    val collapsed: Boolean = false
)

data class NodeGraph(
    val schemaVersion: Int = 1,
    val name: String = "MainWorkflow",
    val nodes: List<GraphNode> = emptyList(),
    val wires: List<GraphWire> = emptyList(),
    val comments: List<CommentBox> = emptyList(),
    val groups: List<NodeGroup> = emptyList()
)

data class NodeValidationIssue(
    val nodeId: String,
    val severity: IssueSeverity,
    val message: String,
    val capability: StudioCapability? = null
) {
    enum class IssueSeverity { ERROR, WARNING, CAPABILITY }
}

enum class CustomDefinitionKind { TRIGGER, ACTION, BLOCK }

enum class BlockLanguage(
    val id: String,
    val label: String,
    val extension: String,
    val runtimeMode: String
) {
    STUDIOLANG("stl", "StudioLang", ".stl", "Direct Binary / Hex + StudioVM"),
    BINARY_HEX("bin", "Binary / Hex", ".bin", "Direct Machine Hex / ELF64"),
    JSON("json", "JSON", ".json", "On-Device Rule Matcher"),
    JAVASCRIPT("js", "JavaScript", ".js", "On-Device / WebView JS"),
    HTML5("html5", "HTML5", ".html", "WebView / Export Asset"),
    KOTLIN("kotlin", "Kotlin", ".kt", "Native Android Export"),
    PYTHON("python", "Python", ".py", "Termux / Export Script"),
    CPP("cpp", "C / C++", ".cpp", "Android NDK / Bare-Metal"),
    RUST("rust", "Rust", ".rs", "Memory-Safe Native / JNI")
}

data class CustomNodeDefinition(
    val id: String,
    val name: String,
    val kind: CustomDefinitionKind,
    val summary: String,
    val params: List<NodeParamSpec>,
    val returnType: PortType,
    val requiredCapabilities: Set<StudioCapability>,
    val sourceCode: String,
    val language: BlockLanguage = BlockLanguage.STUDIOLANG,
    val version: Int = 1
)
