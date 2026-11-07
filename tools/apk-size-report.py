#!/usr/bin/env python3
"""Compare APK sizes and verify that native loaded code/data remain unchanged."""
import argparse
import hashlib
import json
import struct
from pathlib import Path
from zipfile import ZipFile


def loaded_image(data):
    """Hash PT_LOAD content/layout, normalizing only ELF section-table metadata."""
    if data[:4] != b'\x7fELF':
        raise ValueError('Not an ELF library')
    endian = '<' if data[5] == 1 else '>'
    elf64 = data[4] == 2
    offset = struct.unpack_from(endian + ('Q' if elf64 else 'I'), data, 32 if elf64 else 28)[0]
    entry_size, count = struct.unpack_from(endian + 'HH', data, 54 if elf64 else 42)
    result = []
    for i in range(count):
        entry = struct.unpack_from(endian + ('IIQQQQQQ' if elf64 else 'IIIIIIII'), data, offset + i * entry_size)
        if entry[0] != 1:
            continue
        if elf64:
            _, flags, start, address, _, size, memory, alignment = entry
        else:
            _, start, address, _, size, memory, flags, alignment = entry
        content = bytearray(data[start:start + size])
        if start == 0:
            # llvm-strip updates e_shoff/e_shnum/e_shstrndx, which describe non-loaded debug tables.
            shoff, width, shtail = (40, 8, 58) if elf64 else (32, 4, 46)
            content[shoff:shoff + width] = bytes(width)
            content[shtail:shtail + 6] = bytes(6)
        result.append({'flags': flags, 'address': address, 'file_size': size, 'memory_size': memory,
                       'alignment': alignment, 'sha256': hashlib.sha256(content).hexdigest()})
    return result


def inspect(path):
    sizes = {name: 0 for name in ('dex', 'native', 'resources', 'assets', 'other')}
    native = {}
    images = {}
    with ZipFile(path) as apk:
        for item in apk.infolist():
            name = item.filename
            kind = ('native' if name.startswith('lib/') else 'dex' if name.endswith('.dex')
                    else 'resources' if name.startswith('res/') or name == 'resources.arsc'
                    else 'assets' if name.startswith('assets/') else 'other')
            sizes[kind] += item.compress_size
            if kind == 'native' and name.endswith('.so'):
                data = apk.read(item)
                native[name] = hashlib.sha256(data).hexdigest()
                images[name] = loaded_image(data)
    total = Path(path).stat().st_size
    return {'apk_bytes': total, 'payload_bytes': sizes,
            'zip_signing_alignment_bytes': total - sum(sizes.values()), 'native_sha256': native, 'native_loaded_images': images}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('baseline', type=Path)
    parser.add_argument('candidate', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    before, after = inspect(args.baseline), inspect(args.candidate)
    same_native = bool(before['native_sha256']) and before['native_sha256'] == after['native_sha256']
    same_loaded = bool(before['native_loaded_images']) and before['native_loaded_images'] == after['native_loaded_images']
    report = {'baseline': before, 'optimized': after,
              'saved_bytes': before['apk_bytes'] - after['apk_bytes'],
              'saved_percent': round(100 * (1 - after['apk_bytes'] / before['apk_bytes']), 2),
              'native_libraries_identical': same_native, 'native_loaded_images_identical': same_loaded}
    rendered = json.dumps(report, indent=2)
    print(rendered)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + '\n', encoding='utf-8')
    if not same_loaded:
        raise SystemExit('Native loadable content/layout changed; investigate before claiming Xray preservation')


if __name__ == '__main__':
    main()
