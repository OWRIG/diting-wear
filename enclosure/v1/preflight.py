"""Check the supplied dimensions only; exit 2 when the stop condition applies.

Uses the Python standard library. Does not generate meshes or start any apps.
Run from the repository root: python3 enclosure/v1/preflight.py
"""
from pathlib import Path
import hashlib
import json
import math
import re
import sys
import zipfile

HERE = Path(__file__).resolve().parent
DESIGN = HERE.parent / "DESIGN.md"
TEMPLATE = Path(
    "/Users/rig/Documents/ChatGPT/3d printer/Claude/clawd-desk-pet/"
    "v10-peg-fit/plates/41-replacement-pegs-K3-v10.3mf"
)
text = DESIGN.read_text().replace("−", "-")


def line_with(marker):
    matches = [line for line in text.splitlines() if marker in line]
    if len(matches) != 1:
        raise ValueError(f"Expected one design clause: {marker}")
    return matches[0]


def bounds(line, axis):
    match = re.search(rf"{axis} ∈ \[([^\]]+)\]", line)
    if match is None:
        raise ValueError(f"Missing {axis} interval: {line}")
    return [float(v.strip()) for v in match[1].split(",")]


def number(pattern, line):
    match = re.search(pattern, line)
    if match is None:
        raise ValueError(f"Missing dimension: {pattern}")
    return float(match[1])


plate = line_with("1. **盖板**")
tongue = line_with("2. **USB 舌片**")
conduit = line_with("4. **麦克风**")
clip_extent = line_with("整个背夹在")
clip_base = line_with("- **底板**")
clip_tongue = line_with("- **燕尾舌**")
groove = line_with("4. **背夹燕尾槽**")

plate_y = bounds(plate, "y")
tongue_y = bounds(tongue, "y")
lid_gap = tongue_y[0] - plate_y[1]

board_width = float(line_with("| 宽（左右）").split("|")[2])
board_length = float(line_with("| 长（不含 USB").split("|")[2])
board_height = float(line_with("| 厚（USB-C").split("|")[2])
pcb_y = number(r"y = ([\d.]+)", line_with("- PCB 的 USB 侧边缘"))
board_z0 = bounds(plate, "z")[1]
board_bounds = [[-board_width / 2, board_width / 2],
                [pcb_y - board_length, pcb_y],
                [board_z0, board_z0 + board_height]]
outer_r = number(r"外径 ([\d.]+)", conduit) / 2
inner_r = number(r"内径 ([\d.]+)", conduit) / 2
conduit_z = sorted([number(r"从 z=(-?[\d.]+)", conduit),
                    number(r"伸到 z=(-?[\d.]+)", conduit)])
conduit_xy = [float(v) for v in re.search(r"在 \(([^)]+)\)", conduit)[1].split(",")]
assert all(board_bounds[i][0] <= conduit_xy[i] - outer_r and
           conduit_xy[i] + outer_r <= board_bounds[i][1] for i in (0, 1))
overlap_z = [max(board_bounds[2][0], conduit_z[0]),
             min(board_bounds[2][1], conduit_z[1])]
overlap_h = max(0.0, overlap_z[1] - overlap_z[0])
overlap_volume = math.pi * (outer_r**2 - inner_r**2) * overlap_h

clip_xmax = bounds(clip_extent, "x")[1]
root_width = number(r"根部（z=-?[\d.]+）宽 ([\d.]+)", clip_tongue)
tip_width = number(r"顶端（z=-?[\d.]+）宽 ([\d.]+)", clip_tongue)
root_z = number(r"根部（z=(-?[\d.]+)）", clip_tongue)
tip_z = number(r"顶端（z=(-?[\d.]+)）", clip_tongue)
assert bounds(clip_base, "z")[1] == root_z
tip_first_h = clip_xmax - tip_width / 2
root_first_h = clip_xmax - root_width / 2
example_h = 2.7  # A geometric section, not an assumed slicer layer height.
example_x = clip_xmax - example_h
example_inner_z = root_z + (example_x - root_width / 2) * (
    tip_z - root_z) / ((tip_width - root_width) / 2)

mouth_width = number(r"槽口宽 ([\d.]+)", groove)
floor_width = number(r"槽底宽 ([\d.]+)", groove)
groove_depth = number(r"深 ([\d.]+)", groove)
groove_width_at_tip = mouth_width + (floor_width - mouth_width) * (
    tip_z - root_z) / groove_depth

blockers = []
if lid_gap > 0:
    blockers.append({
        "id": "disconnected_usb_tongue",
        "design_lines": [70, 71],
        "plate_y_mm": plate_y,
        "tongue_y_mm": tongue_y,
        "separation_mm": round(lid_gap, 6),
        "result": "盖板与舌片不相接；按原尺寸后盖至少两个实体，不能满足连通体为 1。",
        "verification": "解析区间检查；不是网格实测。",
    })
if overlap_volume > 0.01:
    blockers.append({
        "id": "requested_board_envelope_intersects_conduit",
        "design_lines": [15, 50, 51, 52, 61],
        "board_bounds_xyz_mm": board_bounds,
        "conduit_center_xy_mm": conduit_xy,
        "conduit_outer_inner_radius_mm": [outer_r, inner_r],
        "conduit_bounds_z_mm": conduit_z,
        "overlap_bounds_z_mm": overlap_z,
        "overlap_volume_mm3": round(overlap_volume, 9),
        "formula": "pi * (1.4**2 - 0.75**2) * 1.4",
        "result": "前壳导声管侵入指定矩形包络盒；不等于已证明实际元件干涉。",
        "verification": "理想圆环柱与矩形包络的解析计算；不是布尔网格实测。",
    })
if tip_first_h < root_first_h:
    blockers.append({
        "id": "side_print_dovetail_starts_in_air",
        "design_lines": [78, 80, 85, 93, 95],
        "exact_transform": "(x,y,z) -> (z,y,6-x), plus XY translation",
        "root_width_mm": root_width,
        "tip_width_mm": tip_width,
        "head_first_print_z_mm": round(tip_first_h, 6),
        "root_first_print_z_mm": round(root_first_h, 6),
        "unattached_start_height_mm": round(root_first_h - tip_first_h, 6),
        "example_section_print_z_mm": example_h,
        "example_section_gap_to_base_in_print_x_mm": round(example_inner_z - root_z, 6),
        "example_section_tongue_print_x_mm": [round(example_inner_z, 6), tip_z],
        "result": "侧放时燕尾凸头先于根部出现，初始截面与底板分离，无法保证无支撑成形。",
        "verification": "精确轴置换后的截面解析检查；未运行切片器。",
    })

with zipfile.ZipFile(TEMPLATE) as archive:
    assert archive.testzip() is None
    settings = json.loads(archive.read("Metadata/project_settings.config"))

report = {
    "stage": "blocked_design_conflict" if blockers else "preflight_only",
    "unit": "millimeter",
    "method": "Analytical checks of the supplied design; no modeled mesh exists.",
    "design": {"path": str(DESIGN),
               "sha256": hashlib.sha256(DESIGN.read_bytes()).hexdigest()},
    "template": {"path": str(TEMPLATE), "zip_crc_valid": True,
                 "sha256": hashlib.sha256(TEMPLATE.read_bytes()).hexdigest(),
                 "machine": settings.get("printer_settings_id"),
                 "filament": settings.get("filament_settings_id")},
    "blockers": blockers,
    "additional_dimension_note": {
        "dovetail_root_side_gap_mm": round((mouth_width - root_width) / 2, 6),
        "groove_width_at_tongue_top_mm": round(groove_width_at_tip, 6),
        "dovetail_top_side_gap_mm": round((groove_width_at_tip - tip_width) / 2, 6),
        "note": "线性截面假设下间隙为正，但顶端每侧 0.07，并非文字所述的全程 0.15。",
    },
    "modeling_performed": False,
    "mesh_validation_performed": False,
    "blender_invoked": False,
    "slicer_invoked": False,
    "estimated_time_seconds": None,
    "estimated_filament_g": None,
    "machine_actions_performed_by_agent": False,
    "physical_result_verified": False,
    "command": "python3 enclosure/v1/preflight.py",
    "extra_environment_variables": {},
    "expected_exit_code": 2 if blockers else 0,
}
output = HERE / "preflight-report.json"
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
print(f"{report['stage']}: {len(blockers)} blockers; {output}")
sys.exit(report["expected_exit_code"])
