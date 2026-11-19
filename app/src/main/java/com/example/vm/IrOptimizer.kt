package com.example.vm

import com.example.lang.BinaryOp
import com.example.lang.UnaryOp
import kotlin.math.floor
import kotlin.math.pow

/**
 * Multi-pass IR Optimizer implementing:
 * 1. Constant folding (arithmetic, floor div, bitwise ops, string concatenation, boolean identities)
 * 2. Algebraic identity elimination (x + 0, x * 1, not not x)
 * 3. Constant branch pruning & Dead-code elimination
 * 4. Redundant jump-to-next-label removal & Jump threading
 */
object IrOptimizer {

    data class OptimizationStats(
        val originalOps: Int,
        val optimizedOps: Int,
        val foldedConstants: Int,
        val deadOpsEliminated: Int,
        val jumpsRemoved: Int
    )

    data class OptimizeResult(
        val module: IrModule,
        val stats: OptimizationStats
    )

    fun optimize(module: IrModule): OptimizeResult {
        var totalOriginal = 0
        var totalOptimized = 0
        var folded = 0
        var deadElim = 0
        var jumpsRemoved = 0

        val optimizedFunctions = module.functions.map { fn ->
            totalOriginal += fn.ops.size

            // Pass 1: Constant & algebraic folding + constant branch folding
            val foldRes = foldConstants(fn.ops)
            folded += foldRes.second

            // Pass 2: Dead code elimination
            val dceRes = eliminateDeadCode(foldRes.first)
            deadElim += dceRes.second

            // Pass 3: Jump threading & fallthrough jump removal
            val threaded = threadJumps(dceRes.first)
            val jumpRes = removeFallthroughJumps(threaded)
            jumpsRemoved += jumpRes.second

            totalOptimized += jumpRes.first.size
            fn.copy(ops = jumpRes.first)
        }

        return OptimizeResult(
            module = module.copy(functions = optimizedFunctions),
            stats = OptimizationStats(
                originalOps = totalOriginal,
                optimizedOps = totalOptimized,
                foldedConstants = folded,
                deadOpsEliminated = deadElim,
                jumpsRemoved = jumpsRemoved
            )
        )
    }

    private fun foldConstants(ops: List<IrOp>): Pair<List<IrOp>, Int> {
        val out = mutableListOf<IrOp>()
        var folds = 0
        for (op in ops) {
            when (op) {
                is IrUnary -> {
                    val last = out.lastOrNull()
                    if (last is IrLoadConst) {
                        val foldedVal = evalUnary(op.op, last.value)
                        if (foldedVal != null) {
                            out[out.lastIndex] = IrLoadConst(foldedVal, op.line)
                            folds++
                            continue
                        }
                    } else if (op.op == UnaryOp.NOT && last is IrUnary && last.op == UnaryOp.NOT) {
                        out.removeAt(out.lastIndex)
                        folds++
                        continue
                    }
                    out += op
                }
                is IrBinary -> {
                    if (out.size >= 2) {
                        val b = out[out.lastIndex]
                        val a = out[out.lastIndex - 1]
                        if (a is IrLoadConst && b is IrLoadConst) {
                            val foldedVal = evalBinary(a.value, op.op, b.value)
                            if (foldedVal != null) {
                                out.removeAt(out.lastIndex)
                                out[out.lastIndex] = IrLoadConst(foldedVal, op.line)
                                folds++
                                continue
                            }
                        } else if (b is IrLoadConst && b.value is NumVal) {
                            // Algebraic identities: x + 0 -> x, x * 1 -> x, x - 0 -> x
                            if ((op.op == BinaryOp.ADD || op.op == BinaryOp.SUB) && b.value.value == 0.0) {
                                out.removeAt(out.lastIndex)
                                folds++
                                continue
                            }
                            if (op.op == BinaryOp.MUL && b.value.value == 1.0) {
                                out.removeAt(out.lastIndex)
                                folds++
                                continue
                            }
                        }
                    }
                    out += op
                }
                is IrJumpIfFalse -> {
                    val last = out.lastOrNull()
                    if (last is IrLoadConst) {
                        out.removeAt(out.lastIndex)
                        if (!last.value.asBool()) {
                            out += IrJump(op.targetLabel, op.line)
                        }
                        folds++
                        continue
                    }
                    out += op
                }
                is IrJumpIfTrue -> {
                    val last = out.lastOrNull()
                    if (last is IrLoadConst) {
                        out.removeAt(out.lastIndex)
                        if (last.value.asBool()) {
                            out += IrJump(op.targetLabel, op.line)
                        }
                        folds++
                        continue
                    }
                    out += op
                }
                else -> out += op
            }
        }
        return out to folds
    }

    private fun evalUnary(op: UnaryOp, v: StudioValue): StudioValue? = when {
        op == UnaryOp.NEG && v is NumVal -> NumVal(-v.value)
        op == UnaryOp.BIT_NOT && v is NumVal -> NumVal(v.value.toLong().inv().toDouble())
        op == UnaryOp.NOT -> BoolVal(!v.asBool())
        else -> null
    }

    private fun evalBinary(l: StudioValue, op: BinaryOp, r: StudioValue): StudioValue? = try {
        when (op) {
            BinaryOp.ADD -> when {
                l is NumVal && r is NumVal -> NumVal(l.value + r.value)
                l is TextVal && r is TextVal && !l.isSecret && !r.isSecret -> TextVal(l.value + r.value)
                else -> null
            }
            BinaryOp.SUB -> if (l is NumVal && r is NumVal) NumVal(l.value - r.value) else null
            BinaryOp.MUL -> if (l is NumVal && r is NumVal) NumVal(l.value * r.value) else null
            BinaryOp.DIV -> if (l is NumVal && r is NumVal && r.value != 0.0) NumVal(l.value / r.value) else null
            BinaryOp.FLOOR_DIV -> if (l is NumVal && r is NumVal && r.value != 0.0) NumVal(floor(l.value / r.value)) else null
            BinaryOp.MOD -> if (l is NumVal && r is NumVal && r.value != 0.0) NumVal(l.value % r.value) else null
            BinaryOp.POW -> if (l is NumVal && r is NumVal) NumVal(l.value.pow(r.value)) else null
            BinaryOp.BIT_AND -> if (l is NumVal && r is NumVal) NumVal((l.value.toLong() and r.value.toLong()).toDouble()) else null
            BinaryOp.BIT_OR -> if (l is NumVal && r is NumVal) NumVal((l.value.toLong() or r.value.toLong()).toDouble()) else null
            BinaryOp.BIT_XOR -> if (l is NumVal && r is NumVal) NumVal((l.value.toLong() xor r.value.toLong()).toDouble()) else null
            BinaryOp.SHL -> if (l is NumVal && r is NumVal) NumVal((l.value.toLong() shl r.value.toInt()).toDouble()) else null
            BinaryOp.SHR -> if (l is NumVal && r is NumVal) NumVal((l.value.toLong() shr r.value.toInt()).toDouble()) else null
            BinaryOp.EQ -> BoolVal(l == r)
            BinaryOp.NEQ -> BoolVal(l != r)
            BinaryOp.LT -> if (l is NumVal && r is NumVal) BoolVal(l.value < r.value) else null
            BinaryOp.LTE -> if (l is NumVal && r is NumVal) BoolVal(l.value <= r.value) else null
            BinaryOp.GT -> if (l is NumVal && r is NumVal) BoolVal(l.value > r.value) else null
            BinaryOp.GTE -> if (l is NumVal && r is NumVal) BoolVal(l.value >= r.value) else null
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun eliminateDeadCode(ops: List<IrOp>): Pair<List<IrOp>, Int> {
        val out = mutableListOf<IrOp>()
        var dead = false
        var eliminated = 0
        for (op in ops) {
            if (op is IrLabel) {
                dead = false
                out += op
                continue
            }
            if (dead) {
                eliminated++
                continue
            }
            out += op
            if (op is IrReturn || op is IrJump || op is IrRaise) {
                dead = true
            }
        }
        return out to eliminated
    }

    private fun threadJumps(ops: List<IrOp>): List<IrOp> {
        val redirect = mutableMapOf<Int, Int>()
        for (i in 0 until ops.lastIndex) {
            val cur = ops[i]
            val next = ops[i + 1]
            if (cur is IrLabel && next is IrJump && cur.id != next.targetLabel) {
                redirect[cur.id] = next.targetLabel
            }
        }
        if (redirect.isEmpty()) return ops
        fun resolve(lbl: Int): Int {
            var cur = lbl
            var steps = 0
            while (cur in redirect && steps++ < 16) cur = redirect.getValue(cur)
            return cur
        }
        return ops.map { op ->
            when (op) {
                is IrJump -> op.copy(targetLabel = resolve(op.targetLabel))
                is IrJumpIfFalse -> op.copy(targetLabel = resolve(op.targetLabel))
                is IrJumpIfTrue -> op.copy(targetLabel = resolve(op.targetLabel))
                else -> op
            }
        }
    }

    private fun removeFallthroughJumps(ops: List<IrOp>): Pair<List<IrOp>, Int> {
        val out = mutableListOf<IrOp>()
        var removed = 0
        for (i in ops.indices) {
            val op = ops[i]
            val next = ops.getOrNull(i + 1)
            if (op is IrJump && next is IrLabel && op.targetLabel == next.id) {
                removed++
                continue
            }
            out += op
        }
        return out to removed
    }
}
