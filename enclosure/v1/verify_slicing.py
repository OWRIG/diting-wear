"""Audit native settings and the locally exported G-code (standard library).

python3 enclosure/v1/verify_slicing.py
Optional diagnostic plot: run with matplotlib in a temporary uv environment, --plot.
Does not run any printer instructions or make network/device connections.
"""
from pathlib import Path
from collections import Counter, defaultdict
import argparse
import hashlib
import json
import math
import re
import zipfile
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
PROJECT, GCODE = HERE/'diting-case-v1-K3.3mf', HERE/'diting-case-v1-K3.gcode'
EXPECTED = {'layer_height': '0.16', 'initial_layer_print_height': '0.16', 'wall_loops': '3',
            'sparse_infill_density': '100%', 'enable_support': '0', 'brim_type': 'no_brim',
            'brim_width': '0', 'skirt_loops': '0', 'enable_prime_tower': '0'}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def audit():
    with zipfile.ZipFile(PROJECT) as archive:
        assert archive.testzip() is None
        settings = json.loads(archive.read('Metadata/project_settings.config'))
        config = ET.fromstring(archive.read('Metadata/model_settings.config'))
        ns = {'m': 'http://schemas.microsoft.com/3dmanufacturing/core/2015/02'}
        main = ET.fromstring(archive.read('3D/3dmodel.model'))
        assert main.get('unit') == 'millimeter'
        assert len(main.findall('m:build/m:item', ns)) == 3
        native_overrides = {}
        for obj in config.findall('object'):
            metadata = {v.get('key'): v.get('value') for v in obj.findall('metadata')}
            name = metadata['name']
            native_overrides[name] = {key: metadata.get(key) for key in EXPECTED}
            assert all(metadata.get(key) == value for key, value in EXPECTED.items())
            assert metadata['extruder'] == '1'
        assert all(settings[key] == value for key, value in EXPECTED.items())
        assert set(EXPECTED) <= set(settings['different_settings_to_system'][0].split(';'))

    text = GCODE.read_text()
    lines = text.splitlines()
    effective = dict(re.findall(r'^; (\w+) = ([^\n]*)$', text, re.M))
    assert all(effective.get(key) == value for key, value in EXPECTED.items()), {
        key: effective.get(key) for key in EXPECTED}
    invocation = json.loads((HERE/'slice-invocation.json').read_text())
    assert invocation['exit_code'] == 0 and invocation['project_sha256'] == sha(PROJECT)
    assert invocation['gcode_sha256'] == sha(GCODE) == sha(HERE/'sliced'/'plate_1.gcode')
    layer_z = [float(v) for v in re.findall(r'^;Z:([\d.]+)$', text, re.M)]
    layer_h = [float(v) for v in re.findall(r'^;HEIGHT:([\d.]+)$', text, re.M)]
    assert len(layer_z) == len(layer_h) == 95
    assert all(abs(z-(i+1)*.16) < .00002 for i, z in enumerate(layer_z))
    assert all(abs(h-.16) < .00002 for h in layer_h)

    # Decode actual positive-extrusion paths, including XY I/J arcs and E modes.
    position = dict(X=0., Y=0., Z=0., E=0.)
    absolute_xyz, relative_e = True, True
    feature, obj, current_z = 'Custom', None, 0.
    segments, feature_mm, feature_commands = [], Counter(), Counter()
    object_layers = defaultdict(set)
    startup_positive_e, arcs = 0., 0
    for line_no, raw in enumerate(lines, 1):
        if raw.startswith(';TYPE:'):
            feature = raw[6:]
        if raw.startswith(';Z:'):
            current_z = float(raw[3:])
        if raw.startswith('EXCLUDE_OBJECT_START NAME='):
            identifier = raw.split('=', 1)[1]
            obj = next((n for n in native_overrides if identifier.startswith(n+'_')), None)
            assert obj, identifier
        if raw.startswith('EXCLUDE_OBJECT_END'):
            obj = None
        code = raw.split(';', 1)[0].strip()
        if not code:
            continue
        command = code.split()[0]
        parameters = {k: float(v) for k, v in re.findall(r'([XYZEFIJKR])\s*([-+]?(?:\d*\.\d+|\d+\.?\d*))', code)}
        if command in ('G90', 'G91'):
            absolute_xyz = command == 'G90'
        elif command in ('M82', 'M83'):
            relative_e = command == 'M83'
        elif command == 'G92':
            for k in 'XYZE':
                if k in parameters: position[k] = parameters[k]
        elif command in ('G0', 'G1', 'G2', 'G3'):
            start = position.copy()
            for key in 'XYZ':
                if key in parameters:
                    position[key] = parameters[key] if absolute_xyz else position[key]+parameters[key]
            delta_e = parameters.get('E', 0) if relative_e else parameters.get('E', position['E'])-position['E']
            if 'E' in parameters:
                position['E'] = position['E']+parameters['E'] if relative_e else parameters['E']
            if delta_e <= 0:
                continue
            if obj is None:
                startup_positive_e += delta_e
                continue
            points = [(start['X'], start['Y'])]
            if command in ('G2', 'G3'):
                assert 'I' in parameters or 'J' in parameters, (line_no, code)
                cx, cy = start['X']+parameters.get('I', 0), start['Y']+parameters.get('J', 0)
                r0 = math.hypot(start['X']-cx, start['Y']-cy)
                r1 = math.hypot(position['X']-cx, position['Y']-cy)
                assert abs(r0-r1) < .06, (line_no, r0, r1)
                a0 = math.atan2(start['Y']-cy, start['X']-cx)
                a1 = math.atan2(position['Y']-cy, position['X']-cx)
                sweep = (a1-a0) % (2*math.pi) if command == 'G3' else -((a0-a1) % (2*math.pi))
                if abs(sweep) < 1e-10: sweep = 2*math.pi if command == 'G3' else -2*math.pi
                count = max(2, math.ceil(abs(sweep)*max(r0, r1)/.12), math.ceil(abs(sweep)/math.radians(4)))
                for j in range(1, count):
                    radius = r0+(r1-r0)*j/count
                    points.append((cx+radius*math.cos(a0+sweep*j/count), cy+radius*math.sin(a0+sweep*j/count)))
                arcs += 1
            points.append((position['X'], position['Y']))
            length = sum(math.dist(a, b) for a, b in zip(points, points[1:]))
            if length < 1e-8:
                continue  # stationary unretraction
            assert abs(position['Z']-current_z) < .00002, (line_no, position['Z'], current_z)
            feature_commands[feature] += 1
            feature_mm[feature] += length
            object_layers[obj].add(round(current_z, 5))
            for a, b in zip(points, points[1:]):
                if math.dist(a, b) > 1e-8:
                    segments.append((obj, round(current_z, 5), feature, a, b))

    forbidden = {k: v for k, v in feature_commands.items()
                 if any(t in k.lower() for t in ('support', 'brim', 'skirt', 'prime tower'))}
    assert not forbidden, forbidden
    allowed = {'Inner wall', 'Outer wall', 'Overhang wall', 'Gap infill', 'Internal solid infill',
               'Bottom surface', 'Top surface', 'Bridge'}
    assert set(feature_commands) <= allowed, feature_commands
    geometry = json.loads((HERE/'geometry-report.json').read_text())
    placements = geometry['native_3mf']['placements_xy_mm']
    observed = {}
    for name, part in geometry['parts'].items():
        points = [p for obj, z, feature, a, b in segments if obj == name for p in (a, b)]
        dx, dy = placements[name]
        expected_bounds = part['print_stl']['bounds_mm']
        for p in points:
            assert expected_bounds[0][0]+dx-.3 <= p[0] <= expected_bounds[0][1]+dx+.3, (name, p)
            assert expected_bounds[1][0]+dy-.3 <= p[1] <= expected_bounds[1][1]+dy+.3, (name, p)
        observed[name] = {'layers_with_extrusion': len(object_layers[name]),
                          'first_extrusion_z_mm': min(object_layers[name]),
                          'last_extrusion_z_mm': max(object_layers[name]),
                          'xy_centerline_bounds_mm': [[min(p[i] for p in points), max(p[i] for p in points)] for i in (0, 1)]}
    assert [observed[n]['layers_with_extrusion'] for n in native_overrides] == [95, 63, 75]
    bridges = [{'part': obj, 'z_mm': z} for obj, z, feature, a, b in segments if feature == 'Bridge']
    bridge_layers = sorted({(v['part'], v['z_mm']) for v in bridges})
    time_text = re.search(r'estimated printing time \(normal mode\) = (.+)', text)[1].strip()
    time_seconds = sum(int(value)*{'h': 3600, 'm': 60, 's': 1}[unit] for value, unit in re.findall(r'(\d+)\s*([hms])', time_text))
    def scalar(pattern):
        return float(re.search(pattern, text)[1])
    report = {'stage': 'sliced', 'slicer': re.search(r'generated by (.+?) on ', text)[1],
        'project': PROJECT.name, 'project_sha256': sha(PROJECT), 'gcode': GCODE.name, 'gcode_sha256': sha(GCODE),
        'geometry_report_sha256': sha(HERE/'geometry-report.json'), 'invocation': invocation,
        'machine': settings['printer_settings_id'], 'nozzle_diameter_mm': settings['nozzle_diameter'],
        'material': settings['filament_settings_id'], 'bed_type': settings['curr_bed_type'],
        'estimated_printing_time': time_text, 'estimated_printing_time_seconds': time_seconds,
        'filament_used_g': scalar(r'; filament used \[g\] = ([\d.]+)'),
        'filament_used_mm': scalar(r'; filament used \[mm\] = ([\d.]+)'),
        'filament_used_cm3': scalar(r'; filament used \[cm3\] = ([\d.]+)'),
        'layer_count': len(layer_z), 'actual_layer_height_range_mm': [min(layer_h), max(layer_h)],
        'effective_settings_from_gcode': {key: effective[key] for key in EXPECTED},
        'native_object_overrides': native_overrides, 'different_settings_to_system_contains_all_overrides': True,
        'toolpath_checks': {'features_extrusion_commands': dict(feature_commands),
                            'features_path_length_mm': dict(feature_mm), 'forbidden_feature_extrusion': forbidden,
                            'all_model_extrusion_within_part_bounds': True, 'objects': observed,
                            'bridge_layers': bridge_layers, 'arc_commands_decoded': arcs,
                            'positive_extrusion_segments_decoded': len(segments),
                            'support_present': False, 'brim_present': False, 'skirt_present': False, 'prime_tower_present': False,
                            'startup_positive_E_mm': startup_positive_e,
                            'startup_note': 'Inherited K3 startup includes a purge line at Y=0. It is not model support or brim.',
                            'method': 'global + object + dirty-key + G-code settings; all positive model extrusion decoded including I/J arcs; typed feature and spatial checks'},
        'preview_review': 'pending; see visual-review.json after inspection',
        'machine_actions_performed_by_agent': False, 'physical_result_verified': False,
        'verification_command': 'python3 enclosure/v1/verify_slicing.py', 'extra_environment_variables': {}}
    review_path = HERE/'visual-review.json'
    if review_path.exists():
        review = json.loads(review_path.read_text())
        if review.get('design_sha256') == geometry['design_sha256']:
            report['preview_review'] = review
    (HERE/'slicing-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    print(f'SLICE_QA_OK: {time_text}, {report["filament_used_g"]} g, {len(layer_z)} layers; support/brim absent')
    return segments


def plot(segments):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    from matplotlib.collections import LineCollection
    palette = {'case-front': '#2477a1', 'case-back': '#ae7516', 'clip': '#278c5f'}
    heights = [.16, .48, 1.12, 3.2, 12., 15.2]
    fig, axes = plt.subplots(3, 2, figsize=(13, 12))
    for axis, height in zip(axes.flat, heights):
        for name, color in palette.items():
            lines = [[a, b] for obj, z, feature, a, b in segments if obj == name and abs(z-height) < .00001]
            axis.add_collection(LineCollection(lines, colors=color, linewidths=.6))
        axis.set(xlim=(81, 151), ylim=(98.5, 134), aspect='equal', title=f'Extrusion paths Z={height:.2f} mm', xlabel='X (mm)', ylabel='Y (mm)')
        axis.grid(alpha=.15)
    fig.suptitle('Diting v1 / actual K3 G-code / blue: front, gold: back, green: clip', fontsize=12)
    fig.tight_layout()
    fig.savefig(HERE/'sliced'/'toolpaths.png', dpi=160)
    plt.close(fig)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--plot', action='store_true')
    arguments = parser.parse_args()
    paths = audit()
    if arguments.plot:
        plot(paths)
