package com.example.lang

data class ParamSpec(
    val name: String,
    val typeHint: StudioType? = null,
    val defaultValue: Expr? = null,
    val span: SourceSpan,
    val isVarArg: Boolean = false,
    val isKwArg: Boolean = false
)

data class ArgSpec(
    val name: String? = null,
    val value: Expr,
    val span: SourceSpan,
    val isSpread: Boolean = false,
    val isKwSpread: Boolean = false
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
    override val span: SourceSpan,
    val importedNames: List<Pair<String, String?>> = emptyList()
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

enum class AssignOp { SET, ADD, SUB, MUL, DIV, MOD, FLOOR_DIV, POW }

data class AssignStmt(
    val target: Expr,
    val op: AssignOp,
    val value: Expr,
    override val span: SourceSpan
) : Stmt

data class UnpackAssignStmt(
    val targets: List<String>,
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

data class ClassDefStmt(
    val name: String,
    val baseClass: String? = null,
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
    override val span: SourceSpan,
    val targets: List<String> = listOf(itemName)
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
data class PassStmt(override val span: SourceSpan) : Stmt
data class ReturnStmt(val value: Expr?, override val span: SourceSpan) : Stmt
data class YieldStmt(val value: Expr?, override val span: SourceSpan) : Stmt
data class DelStmt(val target: Expr, override val span: SourceSpan) : Stmt
data class AssertStmt(val condition: Expr, val messageExpr: Expr?, override val span: SourceSpan) : Stmt
data class GlobalDeclStmt(val names: List<String>, override val span: SourceSpan) : Stmt

data class WithStmt(
    val contextExpr: Expr,
    val alias: String?,
    val body: List<Stmt>,
    override val span: SourceSpan
) : Stmt

data class MatchCase(
    val pattern: Expr?,
    val guard: Expr?,
    val body: List<Stmt>,
    val span: SourceSpan
)

data class MatchStmt(
    val subject: Expr,
    val cases: List<MatchCase>,
    override val span: SourceSpan
) : Stmt

data class TryStmt(
    val tryBody: List<Stmt>,
    val errorVar: String?,
    val catchBody: List<Stmt>,
    override val span: SourceSpan,
    val finallyBody: List<Stmt> = emptyList(),
    val exceptType: String? = null
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
data class TupleLit(val elements: List<Expr>, override val span: SourceSpan) : Expr
data class SetLit(val elements: List<Expr>, override val span: SourceSpan) : Expr
data class MapEntryLit(
    val key: String,
    val value: Expr,
    val span: SourceSpan,
    val keyExpr: Expr? = null
)
data class MapLit(val entries: List<MapEntryLit>, override val span: SourceSpan) : Expr
data class VarRef(val name: String, override val span: SourceSpan) : Expr
data class MemberAccess(val target: Expr, val member: String, override val span: SourceSpan) : Expr
data class IndexAccess(val target: Expr, val index: Expr, override val span: SourceSpan) : Expr
data class SliceAccess(
    val target: Expr,
    val start: Expr?,
    val stop: Expr?,
    val step: Expr?,
    override val span: SourceSpan
) : Expr
data class CallExpr(val callee: Expr, val args: List<ArgSpec>, override val span: SourceSpan) : Expr

data class TernaryExpr(
    val trueExpr: Expr,
    val condition: Expr,
    val falseExpr: Expr,
    override val span: SourceSpan
) : Expr

data class LambdaExpr(
    val params: List<ParamSpec>,
    val body: Expr,
    override val span: SourceSpan
) : Expr

data class ListCompExpr(
    val elementExpr: Expr,
    val varNames: List<String>,
    val iterable: Expr,
    val filterExpr: Expr?,
    override val span: SourceSpan
) : Expr

data class DictCompExpr(
    val keyExpr: Expr,
    val valueExpr: Expr,
    val varNames: List<String>,
    val iterable: Expr,
    val filterExpr: Expr?,
    override val span: SourceSpan
) : Expr

data class SetCompExpr(
    val elementExpr: Expr,
    val varNames: List<String>,
    val iterable: Expr,
    val filterExpr: Expr?,
    override val span: SourceSpan
) : Expr

enum class UnaryOp { NEG, NOT, BIT_NOT }
data class UnaryExpr(val op: UnaryOp, val operand: Expr, override val span: SourceSpan) : Expr

enum class BinaryOp(val symbol: String) {
    ADD("+"), SUB("-"), MUL("*"), DIV("/"), FLOOR_DIV("//"), MOD("%"), POW("**"),
    BIT_AND("&"), BIT_OR("|"), BIT_XOR("^"), SHL("<<"), SHR(">>"),
    EQ("=="), NEQ("!="), LT("<"), LTE("<="), GT(">"), GTE(">="),
    AND("and"), OR("or"), IN("in"), NOT_IN("not in"), IS("is"), IS_NOT("is not")
}
data class BinaryExpr(
    val left: Expr,
    val op: BinaryOp,
    val right: Expr,
    override val span: SourceSpan
) : Expr
