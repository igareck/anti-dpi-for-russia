"""Reuse native libraries that match this release's SHA-256 manifest."""

import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    expected = json.loads((root / "NATIVE_LIBRARIES_SHA256.json").read_text())
    with ZipFile(args.apk) as apk:
        libraries = {name: apk.read(name) for name in expected}
    for name, data in libraries.items():
        if hashlib.sha256(data).hexdigest() != expected[name]:
            raise SystemExit(f"Native library hash mismatch: {name}")
    for name, data in libraries.items():
        target = args.output / "stage" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    print(f"Verified and staged {len(libraries)} native libraries")


if __name__ == "__main__":
    main()
