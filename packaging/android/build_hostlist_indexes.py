#!/usr/bin/env python3
"""Детерминированный exact hostlist index: offsets + UTF-8, без HashSet на телефоне."""
import argparse
import hashlib
import json
from pathlib import Path
import struct

MAGIC = b'QNZHL1\0\0'


def normalize_line(line):
    value = line.split('#', 1)[0].strip()
    if not value or value.startswith('!'):
        return None
    value = value.strip('\"\'')
    for prefix in ['||', '*.', '.']:
        if value.startswith(prefix):
            value = value[len(prefix):]
    for separator in ['/', '^', '$']:
        value = value.split(separator, 1)[0]
    value = value.strip().strip('\"\'').split(':', 1)[0].rstrip('.').lower()
    return value if value and '.' in value and not any(c.isspace() for c in value) else None


def build_index(lines):
    domains = sorted({value.encode('utf-8') for line in lines if (value := normalize_line(line))})
    offsets = [0]
    for value in domains:
        offsets.append(offsets[-1] + len(value))
    return MAGIC + struct.pack('<I', len(domains)) + struct.pack('<' + 'I' * len(offsets), *offsets) + b''.join(domains), len(domains)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-dir', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    target = args.output_dir / 'qnzapret/compact-hostlists'
    target.mkdir(parents=True, exist_ok=True)
    manifest = {}
    for source in sorted(args.source_dir.glob('*.txt')):
        with source.open(encoding='utf-8') as stream:
            contents, count = build_index(stream)
        digest = hashlib.sha256(contents).hexdigest()
        name = source.name + '-' + digest[:16] + '.qhi'
        (target / name).write_bytes(contents)
        manifest['qnzapret/lists/' + source.name] = {
            'asset': 'qnzapret/compact-hostlists/' + name,
            'sha256': digest, 'entries': count, 'bytes': len(contents),
            'sourceSha256': hashlib.sha256(source.read_bytes()).hexdigest(),
        }
        print(source.name, count, 'entries;', len(contents), 'bytes')
    (target / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    expected = {item['asset'].split('/')[-1] for item in manifest.values()} | {'manifest.json'}
    for file in target.iterdir():
        if file.name not in expected and file.suffix == '.qhi':
            file.unlink()


if __name__ == '__main__':
    main()
