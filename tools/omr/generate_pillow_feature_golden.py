#!/usr/bin/env python3
"""Generate Pillow 11.1.0 resize fixtures for SvmFeatureExtractor."""

from __future__ import annotations

import gzip
import hashlib
import importlib.metadata
import json
import struct
from pathlib import Path

import numpy as np
from PIL import Image


TARGET_WIDTH = 40
TARGET_HEIGHT = 70
FIXTURE_VERSION = 1
OUTPUT_ROOT = Path("app/src/test/resources/svm_feature_golden")


def cases() -> list[tuple[str, np.ndarray]]:
    rng = np.random.default_rng(0xB1C0B1C)
    dimensions = (
        (1, 1),
        (4, 7),
        (17, 31),
        (35, 60),
        (60, 110),
        (80, 140),
        (13, 23),
        (29, 55),
    )
    result = []
    for width, height in dimensions:
        source = (rng.random((height, width)) > 0.65).astype(np.uint8) * 255
        result.append((f"random_binary_{width}x{height}", source))
    return result


def write_fixture(path: Path, inputs: list[tuple[str, np.ndarray]]) -> str:
    with path.open("wb") as raw_target:
        with gzip.GzipFile(fileobj=raw_target, mode="wb", compresslevel=9, mtime=0) as target:
            target.write(b"SSPF")
            target.write(struct.pack(">ii", FIXTURE_VERSION, len(inputs)))
            for _, source in inputs:
                height, width = source.shape
                expected = np.asarray(
                    Image.fromarray(source).resize((TARGET_WIDTH, TARGET_HEIGHT)),
                    dtype=np.uint8,
                )
                target.write(struct.pack(">ii", width, height))
                target.write(source.tobytes())
                target.write(expected.tobytes())
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    pillow_version = importlib.metadata.version("Pillow")
    if pillow_version != "11.1.0":
        raise RuntimeError(f"Expected Pillow 11.1.0, found {pillow_version}")
    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    inputs = cases()
    fixture_path = OUTPUT_ROOT / "pillow_bicubic.bin.gz"
    fixture_hash = write_fixture(fixture_path, inputs)
    manifest = {
        "fixtureVersion": FIXTURE_VERSION,
        "pillowVersion": pillow_version,
        "targetShape": [TARGET_HEIGHT, TARGET_WIDTH],
        "fixture": fixture_path.name,
        "fixtureSha256": fixture_hash,
        "cases": [name for name, _ in inputs],
    }
    (OUTPUT_ROOT / "manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
