extends Node3D

var demo_cube: MeshInstance3D

func _ready() -> void:
    demo_cube = get_node("DemoCube") as MeshInstance3D

func _process(delta: float) -> void:
    if demo_cube:
        demo_cube.rotate_y(delta * 0.7)
        demo_cube.rotate_x(delta * 0.25)
