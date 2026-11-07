#!/usr/bin/env python3
"""Verify that packaging/stripping preserves the pinned Xray ELF loaded image."""
import argparse
from pathlib import Path
import runpy
from zipfile import ZipFile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('apk', type=Path)
parser.add_argument('--aar', type=Path)
parser.add_argument('--require-stripped', action='store_true')
args = parser.parse_args()
reference = args.aar
if reference is None:
    matches = list((Path.home() / '.gradle/caches/modules-2/files-2.1/io.github.toolshubofficial/libxray/26.6.27').glob('*/*.aar'))
    if len(matches) != 1:
        raise SystemExit('Expected exactly one pinned libxray AAR; pass --aar explicitly')
    reference = matches[0]
image = runpy.run_path(str(Path(__file__).with_name('apk-size-report.py')))['loaded_image']
with ZipFile(reference) as aar, ZipFile(args.apk) as apk:
    libs = [n for n in apk.namelist() if n.startswith('lib/') and n.endswith('/libgojni.so')]
    if not libs:
        raise SystemExit('Xray library missing from APK')
    for name in libs:
        original, shipped = aar.read('jni/' + name.removeprefix('lib/')), apk.read(name)
        before, after = image(original), image(shipped)
        if not before or before != after:
            raise SystemExit(f'{name}: loaded code/data/layout differs from the pinned Xray AAR')
        if any(segment['alignment'] < 16384 for segment in after):
            raise SystemExit(f'{name}: PT_LOAD alignment below 16 KiB')
        if args.require_stripped and len(shipped) >= len(original) * .9:
            raise SystemExit(f'{name}: expected native debug removal did not run; clean build with pinned NDK')
        print(f'{name}: loaded image unchanged, 16 KiB aligned, {len(original)} -> {len(shipped)} bytes')
