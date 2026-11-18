package com.example.ide

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Clean-room Kotlin rebuild of core subsystems from `godotengine/godot` (MIT):
 * - core/object/object (SignalBus)
 * - core/input/input_map (InputMap actions & axes)
 * - servers/physics_2d/godot_physics_server_2d (AABB solver, gravity, damping, bounce, collision signals)
 * - servers/rendering/renderer_canvas_cull (Camera2D viewport offset)
 * - scene/main/node (SceneTree Groups)
 */
object GodotEngineKotlin {

    data class PhysicsStepResult(
        val updatedNodes: List<GodotSceneNode>,
        val collisionStatus: String,
        val emittedSignals: List<String>
    )

    class GodotInputMap {
        private val pressedActions = ConcurrentHashMap.newKeySet<String>()

        fun actionPress(action: String) {
            pressedActions.add(action)
        }

        fun actionRelease(action: String) {
            pressedActions.remove(action)
        }

        fun clearAll() {
            pressedActions.clear()
        }

        fun isActionPressed(action: String): Boolean = pressedActions.contains(action)

        fun getAxis(negativeAction: String, positiveAction: String): Float {
            var axis = 0f
            if (isActionPressed(negativeAction)) axis -= 1f
            if (isActionPressed(positiveAction)) axis += 1f
            return axis
        }
    }

    class GodotSignalBus {
        data class Connection(val targetNode: String, val method: String)

        private val connections = ConcurrentHashMap<String, MutableList<Connection>>()

        fun connect(emitterNode: String, signalName: String, targetNode: String, method: String) {
            val key = "$emitterNode:$signalName"
            val list = connections.getOrPut(key) { mutableListOf() }
            if (list.none { it.targetNode == targetNode && it.method == method }) {
                list += Connection(targetNode, method)
            }
        }

        fun emit(emitterNode: String, signalName: String, detail: String = ""): String {
            val key = "$emitterNode:$signalName"
            val listeners = connections[key].orEmpty()
            return if (listeners.isEmpty()) {
                "Signal: $emitterNode.$signalName($detail)"
            } else {
                val targets = listeners.joinToString(",") { "${it.targetNode}.${it.method}()" }
                "Signal: $emitterNode.$signalName($detail) -> [$targets]"
            }
        }

        fun clear() {
            connections.clear()
        }
    }

    class GodotSceneGroupManager {
        private val groups = ConcurrentHashMap<String, MutableSet<String>>()

        fun addToGroup(nodeName: String, group: String) {
            groups.getOrPut(group) { linkedSetOf() }.add(nodeName)
        }

        fun getNodesInGroup(group: String): Set<String> = groups[group]?.toSet() ?: emptySet()

        fun clear() = groups.clear()
    }

    object PhysicsServer2D {
        fun stepWorld(
            nodes: List<GodotSceneNode>,
            tick: Long,
            inputMap: GodotInputMap,
            signalBus: GodotSignalBus
        ): PhysicsStepResult {
            val emitted = mutableListOf<String>()
            val player = nodes.firstOrNull { it.name == "Player" }

            val inputX = inputMap.getAxis("ui_left", "ui_right")
            val jumpPressed = inputMap.isActionPressed("ui_up") || inputMap.isActionPressed("ui_accept")

            val updated = nodes.map { n ->
                if (n.type == GodotNodeType.CAMERA_2D && player != null) {
                    val followX = n.x + (player.x - n.x) * 0.25f
                    val followY = n.y + (player.y - n.y) * 0.25f
                    return@map n.copy(x = followX.coerceIn(20f, 520f), y = followY.coerceIn(20f, 300f))
                }

                // Static floor / collision shape stays anchored unless scripted velocity moves it
                if (n.type == GodotNodeType.COLLISION_SHAPE_2D && n.gravityScale == 0f && n.vx == 0f && n.vy == 0f) {
                    return@map n
                }

                var curVx = n.vx
                var curVy = n.vy + n.gravityScale * 0.6f

                if (n.name == "Player") {
                    if (inputX != 0f) {
                        curVx = inputX * 4.5f
                    } else if (abs(curVx) > 0.05f && n.scriptPath.isNullOrBlank()) {
                        curVx *= 0.85f
                    }
                    if (jumpPressed && n.y >= 180f) {
                        curVy = -7.5f
                        emitted += signalBus.emit("Player", "jumped", "vy=-7.5")
                    }
                }

                var nx = n.x + curVx
                var ny = n.y + curVy

                if (nx < 20f || nx > 520f) {
                    nx = nx.coerceIn(20f, 520f)
                    curVx = -curVx
                }
                if (ny < 20f || ny > 300f) {
                    ny = ny.coerceIn(20f, 300f)
                    curVy = 0f
                }

                n.copy(x = nx, y = ny, vx = curVx, vy = curVy)
            }

            // Timer timeout signals every 30 ticks (~1s @ 30fps)
            if (tick % 30L == 0L) {
                updated.filter { it.type == GodotNodeType.TIMER }.forEach { timerNode ->
                    emitted += signalBus.emit(timerNode.name, "timeout", "@tick#$tick")
                }
            }

            // Solid AABB resolution & signal emission
            val resolved = updated.toMutableList()
            val solids = updated.indices.filter { updated[it].solidCollision }
            var hitPair: String? = null
            for (ii in solids.indices) {
                for (jj in ii + 1 until solids.size) {
                    val i = solids[ii]
                    val j = solids[jj]
                    val a = resolved[i]
                    val b = resolved[j]
                    val overlapX = (a.width + b.width) * 0.48f - abs(a.x - b.x)
                    val overlapY = (a.height + b.height) * 0.48f - abs(a.y - b.y)
                    if (overlapX > 0f && overlapY > 0f) {
                        hitPair = "Collision: ${a.name} <-> ${b.name}"
                        // Push movable body (e.g., CharacterBody2D) out cleanly when standing on platform
                        if (a.type == GodotNodeType.CHARACTER_BODY_2D && b.type == GodotNodeType.COLLISION_SHAPE_2D && a.vy >= 0f && overlapY <= overlapX) {
                            resolved[i] = a.copy(y = a.y - overlapY, vy = 0f)
                        } else if (b.type == GodotNodeType.CHARACTER_BODY_2D && a.type == GodotNodeType.COLLISION_SHAPE_2D && b.vy >= 0f && overlapY <= overlapX) {
                            resolved[j] = b.copy(y = b.y - overlapY, vy = 0f)
                        }
                        if (tick % 15L == 0L) {
                            emitted += signalBus.emit(a.name, "body_entered", b.name)
                        }
                    }
                }
            }

            return PhysicsStepResult(
                updatedNodes = resolved,
                collisionStatus = hitPair ?: "Tick #$tick · Clean",
                emittedSignals = emitted
            )
        }
    }

    object RenderingServer2D {
        /**
         * Returns camera translation offset so Viewport centers gently on active Camera2D.
         */
        fun computeCameraOffset(nodes: List<GodotSceneNode>): Pair<Float, Float> {
            val cam = nodes.firstOrNull { it.type == GodotNodeType.CAMERA_2D && it.visible } ?: return 0f to 0f
            val dx = (290f - cam.x) * 0.35f
            val dy = (170f - cam.y) * 0.35f
            return dx to dy
        }
    }
}
