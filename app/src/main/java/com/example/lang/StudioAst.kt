package com.example.lang

data class ParamSpec(
    val name: String,
    val typeHint: StudioType? = null,
    val defaultValue: Expr? = null,
    val span: SourceSpan
)

data class ArgSpec(
    val name: String? = null,
    val value: Expr,
    val span: SourceSpan
)

data class ProgramNode(
    val statements: List<Stmt>,
    val declaredNeeds: List<String> = emptyList()
)

sealed interface Stmt {
    val span: SourceSpan
}

data class UseStmt(
    val modulePath: List<String>,
    val alias: String? = null,
    override val span: SourceSpan
) : Stmt

data class NeedsStmt(
    val capabilities: List<String>,
    override val span: SourceSpan
) : Stmt

data class VarDeclStmt(
    val name: String,
    val isConst: Boolean,
    val typeHint: StudioType? = null,
    val initializer: Expr,
    override val span: SourceSpan
) : Stmt

enum class AssignOp { SET, ADD, SUB, MUL, DIV }

data class AssignStmt(
    val target: Expr,
    val op: AssignOp,
    val value: Expr,
    override val span: SourceSpan
) : Stmt

data class FuncDefStmt(
    val name: String,
    val params: List<ParamSpec>,
    val returnType: StudioType? = null,
    val body: List<Stmt>,
    override val span: SourceSpan
) : Stmt

data class TriggerDefStmt(
    val name: String,
    val sourceExpr: Expr?,
    val whenExpr: Expr?,
    val emitExpr: Expr?,
    val needs: List<String>,
    val body: List<Stmt> = emptyList(),
    override val span: SourceSpan
) : Stmt

data class ConditionDefStmt(
    val name: String,
    val params: List<ParamSpec>,
    val returnType: StudioType?,
    val body: List<Stmt>,
    override val span: SourceSpan
) : Stmt

data class ConstraintDefStmt(
    val name: String,
    val params: List<ParamSpec>,
    val returnType: StudioType?,
    val body: List<Stmt>,
    override val span: SourceSpan
) : Stmt

data class ActionDefStmt(
    val name: String,
    val params: List<ParamSpec>,
    val returnType: StudioType?,
    val needs: List<String>,
    val body: List<Stmt>,
    override val span: SourceSpan
) : Stmt

data class BlockDefStmt(
    val name: String,
    val params: List<ParamSpec>,
    val returnType: StudioType?,
    val needs: List<String>,
    val body: List<Stmt>,
    override val span: SourceSpan
) : Stmt

data class MacroDefStmt(
    val name: String,
    val onTrigger: Expr?,
    val onlyIf: Expr?,
    val needs: List<String>,
    val doBody: List<Stmt>,
    val errorVar: String? = null,
    val errorBody: List<Stmt> = emptyList(),
    override val span: SourceSpan
) : Stmt

data class AppDefStmt(
    val name: String,
    val body: List<Stmt>,
    override val span: SourceSpan
) : Stmt

data class ScreenDefStmt(
    val name: String,
    val body: List<Stmt>,
    override val span: SourceSpan
) : Stmt

data class IfBranch(
    val condition: Expr,
    val body: List<Stmt>,
    val span: SourceSpan
)

data class IfStmt(
    val branches: List<IfBranch>,
    val elseBody: List<Stmt> = emptyList(),
    override val span: SourceSpan
) : Stmt

data class ForStmt(
    val itemName: String,
    val iterable: Expr,
    val body: List<Stmt>,
    override val span: SourceSpan
) : Stmt

data class WhileStmt(
    val condition: Expr,
    val body: List<Stmt>,
    override val span: SourceSpan
) : Stmt

data class RepeatStmt(
    val countExpr: Expr,
    val body: List<Stmt>,
    override val span: SourceSpan
) : Stmt

data class BreakStmt(override val span: SourceSpan) : Stmt
data class ContinueStmt(override val span: SourceSpan) : Stmt
data class ReturnStmt(val value: Expr?, override val span: SourceSpan) : Stmt

data class TryStmt(
    val tryBody: List<Stmt>,
    val errorVar: String?,
    val catchBody: List<Stmt>,
    override val span: SourceSpan
) : Stmt

data class RaiseStmt(val messageExpr: Expr, override val span: SourceSpan) : Stmt

data class RetryStmt(
    val countExpr: Expr,
    val waitExpr: Expr?,
    val body: List<Stmt>,
    override val span: SourceSpan
) : Stmt

data class TimeoutStmt(
    val durationExpr: Expr,
    val body: List<Stmt>,
    override val span: SourceSpan
) : Stmt

data class ConfirmStmt(
    val promptExpr: Expr,
    override val span: SourceSpan
) : Stmt

data class ExprStmt(
    val expr: Expr,
    override val span: SourceSpan
) : Stmt

sealed interface Expr {
    val span: SourceSpan
}

data class NumberLit(val value: Double, override val span: SourceSpan) : Expr
data class TextLit(val value: String, override val span: SourceSpan) : Expr
data class BoolLit(val value: Boolean, override val span: SourceSpan) : Expr
data class NoneLit(override val span: SourceSpan) : Expr
data class DurationLit(val millis: Long, val raw: String, override val span: SourceSpan) : Expr
data class ListLit(val elements: List<Expr>, override val span: SourceSpan) : Expr
data class MapEntryLit(val key: String, val value: Expr, val span: SourceSpan)
data class MapLit(val entries: List<MapEntryLit>, override val span: SourceSpan) : Expr
data class VarRef(val name: String, override val span: SourceSpan) : Expr
data class MemberAccess(val target: Expr, val member: String, override val span: SourceSpan) : Expr
data class IndexAccess(val target: Expr, val index: Expr, override val span: SourceSpan) : Expr
data class CallExpr(val callee: Expr, val args: List<ArgSpec>, override val span: SourceSpan) : Expr

enum class UnaryOp { NEG, NOT }
data class UnaryExpr(val op: UnaryOp, val operand: Expr, override val span: SourceSpan) : Expr

enum class BinaryOp(val symbol: String) {
    ADD("+"), SUB("-"), MUL("*"), DIV("/"), MOD("%"), POW("**"),
    EQ("=="), NEQ("!="), LT("<"), LTE("<="), GT(">"), GTE(">="),
    AND("and"), OR("or"), IN("in"), IS("is")
}
data class BinaryExpr(
    val left: Expr,
    val op: BinaryOp,
    val right: Expr,
    override val span: SourceSpan
) : Expr
