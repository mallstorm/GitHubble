#!/usr/bin/env python3
"""Keep debug symbols only for the native ABIs actually shipped in an APK."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('apk', type=Path)
parser.add_argument('symbols', type=Path)
parser.add_argument('output', type=Path)
args = parser.parse_args()
with ZipFile(args.apk) as apk:
    abis = {n.split('/')[1] for n in apk.namelist() if n.startswith('lib/') and n.endswith('.so')}
with ZipFile(args.symbols) as source:
    names = [n for n in source.namelist() if not n.endswith('/') and n.split('/')[0] in abis]
    if not names or {n.split('/')[0] for n in names} != abis:
        raise SystemExit('Debug symbols do not cover every shipped ABI')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(args.output, 'w', compression=ZIP_DEFLATED) as target:
        for name in names:
            target.writestr(name, source.read(name))
print('Native symbol ABIs:', sorted(abis), 'archive bytes:', args.output.stat().st_size)
