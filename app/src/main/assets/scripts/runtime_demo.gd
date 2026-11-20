extends Node3D

var demo_cube: MeshInstance3D
var floor_mesh: MeshInstance3D
var camera_3d: Camera3D
var dir_light: DirectionalLight3D
var world_env: WorldEnvironment
var live_hud_layer: CanvasLayer
var hud_label: Label
var studio_bridge: Object = null
var launch_mode := "demo_3d"
var workspace_root: Node = null
var workspace_overlay_label: Label = null

var spin_velocity := Vector2(0.25, 0.7)
var pulse_timer := 0.0
var is_dragging := false
var palette_index := 0
var colors := [
	Color(0.22, 0.74, 0.97, 1.0),
	Color(0.20, 0.83, 0.60, 1.0),
	Color(0.66, 0.55, 0.98, 1.0),
	Color(0.98, 0.75, 0.14, 1.0)
]

func _ready() -> void:
	demo_cube = get_node_or_null("DemoCube") as MeshInstance3D
	floor_mesh = get_node_or_null("Floor") as MeshInstance3D
	camera_3d = get_node_or_null("Camera3D") as Camera3D
	dir_light = get_node_or_null("DirectionalLight3D") as DirectionalLight3D
	world_env = get_node_or_null("WorldEnvironment") as WorldEnvironment
	live_hud_layer = get_node_or_null("LiveHudLayer") as CanvasLayer
	hud_label = get_node_or_null("LiveHudLayer/HudStatusLabel") as Label

	if Engine.has_singleton("AutomationStudioBridge"):
		studio_bridge = Engine.get_singleton("AutomationStudioBridge")
		if studio_bridge.has_signal("ide_command"):
			studio_bridge.connect("ide_command", Callable(self, "_on_ide_command"))
		if studio_bridge.has_method("getLaunchMode"):
			launch_mode = str(studio_bridge.getLaunchMode())
		if launch_mode == "demo_3d" or launch_mode == "":
			studio_bridge.emitStudioEvent("scene_ready", "Godot 4.7.2-stable 3D Live Interaction Active")
		else:
			_activate_workspace_mode()

func _activate_workspace_mode() -> void:
	if demo_cube:
		demo_cube.visible = false
	if floor_mesh:
		floor_mesh.visible = false
	if dir_light:
		dir_light.visible = false
	if camera_3d:
		camera_3d.current = false
	_load_active_workspace_scene()

func _on_ide_command(cmd: String) -> void:
	if cmd == "reload_workspace":
		if studio_bridge and studio_bridge.has_method("getLaunchMode"):
			launch_mode = str(studio_bridge.getLaunchMode())
		if launch_mode != "demo_3d":
			_activate_workspace_mode()
		return
	pulse_timer = 0.45
	_cycle_cube_color()
	if hud_label and launch_mode == "demo_3d":
		hud_label.text = "IDE Command Received: %s" % cmd
	elif workspace_overlay_label:
		workspace_overlay_label.text = "IDE Pulse: %s" % cmd

func _input(event: InputEvent) -> void:
	if launch_mode != "demo_3d":
		return
	if event is InputEventScreenTouch:
		is_dragging = event.pressed
		if event.pressed:
			pulse_timer = 0.35
			_cycle_cube_color()
			if studio_bridge:
				studio_bridge.emitStudioEvent("touch_tap", "pos=(%d,%d)" % [int(event.position.x), int(event.position.y)])
	elif event is InputEventScreenDrag:
		spin_velocity.y = clamp(event.relative.x * 0.04, -6.0, 6.0)
		spin_velocity.x = clamp(event.relative.y * 0.04, -6.0, 6.0)
		if demo_cube:
			demo_cube.rotate_y(event.relative.x * 0.012)
			demo_cube.rotate_x(event.relative.y * 0.012)
		if hud_label:
			hud_label.text = "Live Drag Spin · Cube Rot=(%.1f, %.1f)" % [demo_cube.rotation_degrees.x, demo_cube.rotation_degrees.y]
	elif event is InputEventMouseButton and event.button_index == MOUSE_BUTTON_LEFT:
		is_dragging = event.pressed
		if event.pressed:
			pulse_timer = 0.35
			_cycle_cube_color()
	elif event is InputEventMouseMotion and is_dragging:
		if demo_cube:
			demo_cube.rotate_y(event.relative.x * 0.012)
			demo_cube.rotate_x(event.relative.y * 0.012)

func _cycle_cube_color() -> void:
	palette_index = (palette_index + 1) % colors.size()
	if demo_cube:
		var mat := demo_cube.get_surface_override_material(0)
		if mat is StandardMaterial3D:
			mat.albedo_color = colors[palette_index]

func _process(delta: float) -> void:
	if launch_mode != "demo_3d":
		return
	if demo_cube:
		if not is_dragging:
			demo_cube.rotate_y(delta * spin_velocity.y)
			demo_cube.rotate_x(delta * spin_velocity.x)
			spin_velocity = spin_velocity.lerp(Vector2(0.25, 0.7), min(1.0, delta * 2.0))
		if pulse_timer > 0.0:
			pulse_timer = max(0.0, pulse_timer - delta)
			var s := 1.0 + 0.25 * sin(pulse_timer * PI * 3.0)
			demo_cube.scale = Vector3(s, s, s)
		else:
			demo_cube.scale = demo_cube.scale.lerp(Vector3.ONE, min(1.0, delta * 6.0))

func _load_active_workspace_scene() -> void:
	if workspace_root != null and is_instance_valid(workspace_root):
		workspace_root.queue_free()
		workspace_root = null
	workspace_overlay_label = null

	if studio_bridge == null:
		return

	if hud_label:
		hud_label.text = "Godot 4.7.2-stable · %s" % launch_mode.capitalize()
		workspace_overlay_label = hud_label

	if launch_mode == "project_manager":
		if hud_label:
			hud_label.text = "Godot 4.7.2 Project Manager Ready · Use Dock to Open or Run Workspace"
		studio_bridge.emitStudioEvent("project_manager_ready", "Godot 4.7.2 Project Manager Active")
		return

	var target_scene_rel := _resolve_main_scene_rel()
	var tscn_text := ""
	if target_scene_rel != "" and studio_bridge.has_method("readWorkspaceFile"):
		tscn_text = str(studio_bridge.readWorkspaceFile(target_scene_rel))

	if tscn_text.strip_edges() == "":
		if hud_label:
			hud_label.text = "Workspace empty · Add nodes or edit files in Godot 4.7.2 Dock"
		studio_bridge.emitStudioEvent("workspace_empty", target_scene_rel)
		return

	var built := _instantiate_tscn_from_text(tscn_text)
	if built == null:
		if hud_label:
			hud_label.text = "Could not parse %s" % target_scene_rel
		return

	workspace_root = built
	add_child(workspace_root)
	if live_hud_layer:
		live_hud_layer.visible = false
	studio_bridge.emitStudioEvent("workspace_scene_loaded", "%s (%s)" % [target_scene_rel, launch_mode])

func _resolve_main_scene_rel() -> String:
	var explicit := str(studio_bridge.getLaunchMainScene()).strip_edges()
	if explicit != "":
		return explicit.trim_prefix("res://")
	var cfg := str(studio_bridge.readWorkspaceFile("project.godot"))
	for raw_line in cfg.split("\n"):
		var line := raw_line.strip_edges()
		if line.begins_with("run/main_scene"):
			var q1 := line.find("\"")
			var q2 := line.rfind("\"")
			if q1 >= 0 and q2 > q1:
				return line.substr(q1 + 1, q2 - q1 - 1).trim_prefix("res://")
	var files_raw := str(studio_bridge.listWorkspaceFiles())
	var candidate := ""
	for f in files_raw.split("\n"):
		var p := f.strip_edges()
		if p == "scenes/main.tscn":
			return p
		if p.ends_with(".tscn") and p != "scenes/studio_3d.tscn" and candidate == "":
			candidate = p
	return candidate

func _instantiate_tscn_from_text(tscn_text: String) -> Node:
	var ext_scripts := {}
	var sub_shapes := {}
	var pending_nodes := []

	var section_kind := ""
	var section_attrs := {}
	var section_props := {}

	var flush_section := func() -> void:
		if section_kind == "ext_resource":
			var res_type := str(section_attrs.get("type", ""))
			var res_id := str(section_attrs.get("id", ""))
			var res_path := str(section_attrs.get("path", "")).trim_prefix("res://")
			if res_type == "Script" and res_id != "" and res_path != "":
				ext_scripts[res_id] = res_path
		elif section_kind == "sub_resource":
			var sub_type := str(section_attrs.get("type", ""))
			var sub_id := str(section_attrs.get("id", ""))
			if sub_id != "":
				sub_shapes[sub_id] = {"type": sub_type, "props": section_props.duplicate()}
		elif section_kind == "node":
			pending_nodes.append({
				"attrs": section_attrs.duplicate(),
				"props": section_props.duplicate()
			})

	for raw_line in tscn_text.split("\n"):
		var line := raw_line.strip_edges()
		if line == "" or line.begins_with(";"):
			continue
		if line.begins_with("[") and line.ends_with("]"):
			flush_section.call()
			section_props.clear()
			var inner := line.substr(1, line.length() - 2).strip_edges()
			var space_idx := inner.find(" ")
			if space_idx == -1:
				section_kind = inner
				section_attrs = {}
			else:
				section_kind = inner.substr(0, space_idx)
				section_attrs = _parse_header_attrs(inner.substr(space_idx + 1))
		else:
			var eq := line.find("=")
			if eq > 0:
				var k := line.substr(0, eq).strip_edges()
				var v := line.substr(eq + 1).strip_edges()
				section_props[k] = v

	flush_section.call()

	if pending_nodes.is_empty():
		return null

	var scene_root: Node = null
	var script_cache := {}

	for entry in pending_nodes:
		var attrs: Dictionary = entry["attrs"]
		var props: Dictionary = entry["props"]
		var node_name := str(attrs.get("name", "Node"))
		var node_type := str(attrs.get("type", "Node2D"))
		var has_parent := attrs.has("parent")
		var parent_rel := str(attrs.get("parent", ""))

		var node := _create_godot_node(node_type)
		node.name = node_name
		_apply_node_properties(node, props, sub_shapes)

		var script_expr := str(props.get("script", ""))
		if script_expr.begins_with("ExtResource("):
			var sid := _extract_quoted_value(script_expr)
			if ext_scripts.has(sid):
				var gd_rel := str(ext_scripts[sid])
				var compiled_script := _load_runtime_gdscript(gd_rel, script_cache)
				if compiled_script != null:
					node.set_script(compiled_script)
					_apply_node_properties(node, props, sub_shapes)

		if not has_parent or scene_root == null:
			scene_root = node
		else:
			var parent_node: Node = scene_root
			if parent_rel != "." and parent_rel != "":
				var found := scene_root.get_node_or_null(parent_rel)
				if found != null:
					parent_node = found
			parent_node.add_child(node)

	return scene_root

func _load_runtime_gdscript(rel_path: String, cache: Dictionary) -> GDScript:
	if cache.has(rel_path):
		return cache[rel_path] as GDScript
	if studio_bridge == null or not studio_bridge.has_method("readWorkspaceFile"):
		return null
	var code := str(studio_bridge.readWorkspaceFile(rel_path))
	if code.strip_edges() == "":
		return null
	var script := GDScript.new()
	script.source_code = code
	var err := script.reload()
	if err != OK:
		studio_bridge.logToIde("GDScript compile error (%d) in %s" % [err, rel_path])
		return null
	cache[rel_path] = script
	return script

func _create_godot_node(node_type: String) -> Node:
	match node_type:
		"Node2D":
			return Node2D.new()
		"Sprite2D":
			return Sprite2D.new()
		"CharacterBody2D":
			return CharacterBody2D.new()
		"StaticBody2D":
			return StaticBody2D.new()
		"CollisionShape2D":
			return CollisionShape2D.new()
		"Camera2D":
			return Camera2D.new()
		"Label":
			return Label.new()
		"Timer":
			return Timer.new()
		"TouchScreenButton":
			return TouchScreenButton.new()
		"AudioStreamPlayer":
			return AudioStreamPlayer.new()
		"CanvasLayer":
			return CanvasLayer.new()
		"ColorRect":
			return ColorRect.new()
		"Control":
			return Control.new()
		"Panel":
			return Panel.new()
		"Button":
			return Button.new()
		"Node3D":
			return Node3D.new()
		"MeshInstance3D":
			return MeshInstance3D.new()
		"Camera3D":
			return Camera3D.new()
		"DirectionalLight3D":
			return DirectionalLight3D.new()
		_:
			return Node2D.new()

func _apply_node_properties(node: Node, props: Dictionary, sub_shapes: Dictionary) -> void:
	for k in props.keys():
		var key := str(k)
		var val := str(props[k])
		match key:
			"position":
				if val.begins_with("Vector2("):
					node.set("position", _parse_vec2(val))
				elif val.begins_with("Vector3("):
					node.set("position", _parse_vec3(val))
			"scale":
				if val.begins_with("Vector2("):
					node.set("scale", _parse_vec2(val))
				elif val.begins_with("Vector3("):
					node.set("scale", _parse_vec3(val))
			"rotation":
				node.set("rotation", val.to_float())
			"rotation_degrees":
				if val.begins_with("Vector3("):
					node.set("rotation_degrees", _parse_vec3(val))
			"visible":
				node.set("visible", val == "true")
			"enabled":
				node.set("enabled", val == "true")
			"current":
				node.set("current", val == "true")
			"text":
				node.set("text", _unquote_string(val))
			"action":
				node.set("action", _unquote_string(val))
			"offset_left":
				node.set("offset_left", val.to_float())
			"offset_top":
				node.set("offset_top", val.to_float())
			"offset_right":
				node.set("offset_right", val.to_float())
			"offset_bottom":
				node.set("offset_bottom", val.to_float())
			"color":
				if val.begins_with("Color("):
					node.set("color", _parse_color(val))
			"shape":
				if val.begins_with("SubResource("):
					var sub_id := _extract_quoted_value(val)
					if sub_shapes.has(sub_id):
						var sub_entry: Dictionary = sub_shapes[sub_id]
						if str(sub_entry.get("type", "")) == "RectangleShape2D":
							var rect_shape := RectangleShape2D.new()
							var sub_props: Dictionary = sub_entry.get("props", {})
							if sub_props.has("size"):
								rect_shape.size = _parse_vec2(str(sub_props["size"]))
							node.set("shape", rect_shape)

func _parse_header_attrs(raw: String) -> Dictionary:
	var out := {}
	var i := 0
	var n := raw.length()
	while i < n:
		while i < n and raw[i] == " ":
			i += 1
		if i >= n:
			break
		var eq := raw.find("=", i)
		if eq == -1:
			break
		var key := raw.substr(i, eq - i).strip_edges()
		i = eq + 1
		while i < n and raw[i] == " ":
			i += 1
		if i < n and raw[i] == "\"":
			i += 1
			var end_q := raw.find("\"", i)
			if end_q == -1:
				out[key] = raw.substr(i)
				break
			out[key] = raw.substr(i, end_q - i)
			i = end_q + 1
		else:
			var sp := raw.find(" ", i)
			if sp == -1:
				out[key] = raw.substr(i)
				break
			out[key] = raw.substr(i, sp - i)
			i = sp + 1
	return out

func _extract_quoted_value(expr: String) -> String:
	var q1 := expr.find("\"")
	var q2 := expr.rfind("\"")
	if q1 >= 0 and q2 > q1:
		return expr.substr(q1 + 1, q2 - q1 - 1)
	return ""

func _unquote_string(raw: String) -> String:
	var s := raw.strip_edges()
	if s.length() >= 2 and s.begins_with("\"") and s.ends_with("\""):
		s = s.substr(1, s.length() - 2)
	return s.replace("\\\"", "\"")

func _parse_vec2(raw: String) -> Vector2:
	var inner := raw.trim_prefix("Vector2(").trim_suffix(")")
	var parts := inner.split(",")
	if parts.size() >= 2:
		return Vector2(parts[0].strip_edges().to_float(), parts[1].strip_edges().to_float())
	return Vector2.ZERO

func _parse_vec3(raw: String) -> Vector3:
	var inner := raw.trim_prefix("Vector3(").trim_suffix(")")
	var parts := inner.split(",")
	if parts.size() >= 3:
		return Vector3(
			parts[0].strip_edges().to_float(),
			parts[1].strip_edges().to_float(),
			parts[2].strip_edges().to_float()
		)
	return Vector3.ZERO

func _parse_color(raw: String) -> Color:
	var inner := raw.trim_prefix("Color(").trim_suffix(")")
	var parts := inner.split(",")
	if parts.size() >= 4:
		return Color(
			parts[0].strip_edges().to_float(),
			parts[1].strip_edges().to_float(),
			parts[2].strip_edges().to_float(),
			parts[3].strip_edges().to_float()
		)
	elif parts.size() == 3:
		return Color(
			parts[0].strip_edges().to_float(),
			parts[1].strip_edges().to_float(),
			parts[2].strip_edges().to_float(),
			1.0
		)
	return Color.WHITE
