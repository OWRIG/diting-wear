"""Slice only on this computer; never connect to or send jobs to a printer."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import shutil
import subprocess
import time

HERE = Path(__file__).resolve().parent
APP = '/Applications/Creality Print.app/Contents/MacOS/CrealityPrint'
PROJECT = HERE/'diting-case-v1-K3.3mf'
OUT = HERE/'sliced'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    OUT.mkdir(exist_ok=True)
    command = [APP, '--cli', '--slice', '1', '--need-gcode-file', '--outputdir', str(OUT), str(PROJECT)]
    record = {'command': command, 'extra_environment_variables': {}, 'cwd': str(HERE),
              'started_utc': datetime.now(timezone.utc).isoformat(), 'project_sha256': sha(PROJECT),
              'machine_actions_performed_by_agent': False}
    started = time.monotonic()
    with (OUT/'slicer.log').open('w') as log:
        result = subprocess.run(command, cwd=HERE, stdout=log, stderr=subprocess.STDOUT, timeout=600)
    gcode = OUT/'plate_1.gcode'
    record.update({'exit_code': result.returncode, 'elapsed_seconds': time.monotonic()-started,
                   'gcode_exists': gcode.exists(), 'gcode_sha256': sha(gcode) if gcode.exists() else None})
    (HERE/'slice-invocation.json').write_text(json.dumps(record, ensure_ascii=False, indent=2)+'\n')
    if result.returncode != 0 or not gcode.exists():
        raise RuntimeError(f'Local slicing failed; inspect {OUT / "slicer.log"}')
    shutil.copyfile(gcode, HERE/'diting-case-v1-K3.gcode')
    print(f'LOCAL_SLICE_OK {gcode.stat().st_size} bytes; exit {result.returncode}')


if __name__ == '__main__':
    main()
