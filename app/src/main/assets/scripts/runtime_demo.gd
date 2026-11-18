extends Node3D

var demo_cube: MeshInstance3D
var camera_3d: Camera3D
var hud_label: Label
var studio_bridge: Object = null
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
	camera_3d = get_node_or_null("Camera3D") as Camera3D
	hud_label = get_node_or_null("LiveHudLayer/HudStatusLabel") as Label
	if Engine.has_singleton("AutomationStudioBridge"):
		studio_bridge = Engine.get_singleton("AutomationStudioBridge")
		if studio_bridge.has_signal("ide_command"):
			studio_bridge.connect("ide_command", Callable(self, "_on_ide_command"))
		studio_bridge.emitStudioEvent("scene_ready", "Godot 4.7.2-stable 3D Live Interaction Active")

func _on_ide_command(cmd: String) -> void:
	pulse_timer = 0.45
	_cycle_cube_color()
	if hud_label:
		hud_label.text = "IDE Command Received: %s" % cmd

func _input(event: InputEvent) -> void:
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
