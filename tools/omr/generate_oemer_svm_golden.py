#!/usr/bin/env python3
"""Generate deterministic sklearn golden vectors for Android SVM parity.

This script must be run with scikit-learn 1.2.0, the version recorded in
oemer 0.1.8's serialized estimators. It reads the official pickle files,
selects decision-boundary support vectors plus deterministic stress inputs,
and writes compact gzip fixtures consumed by ``OemerSvmParityInstrumentedTest``.

When ``--onnx-root`` is supplied, the same vectors are also executed through
the exported ONNX graphs. Any label mismatch fails generation; measured score
deltas are recorded in the manifest rather than hidden behind a tolerance.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import importlib.metadata
import json
import pickle
import struct
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np


FEATURE_WIDTH = 40
FEATURE_HEIGHT = 70
FEATURE_COUNT = FEATURE_WIDTH * FEATURE_HEIGHT
SUPPORT_VECTORS_PER_CLASS = 24
FIXTURE_MAGIC = b"SSSV"
FIXTURE_VERSION = 2


@dataclass(frozen=True)
class ModelConfig:
    source_name: str
    fixture_name: str
    onnx_name: str


MODELS = (
    ModelConfig("clef", "clef.bin.gz", "oemer_clef_svc.onnx"),
    ModelConfig("sfn", "sfn.bin.gz", "oemer_sfn_svc.onnx"),
    ModelConfig("rests", "rests.bin.gz", "oemer_rests_svc.onnx"),
    ModelConfig(
        "rests_above8",
        "rests_above8.bin.gz",
        "oemer_rests_above8_svc.onnx",
    ),
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--model-root",
        type=Path,
        required=True,
        help="Directory containing oemer 0.1.8 sklearn_models/*.model",
    )
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--onnx-root",
        type=Path,
        help="Optional directory containing the converted ONNX assets",
    )
    return parser.parse_args()


def load_estimator(model_path: Path) -> Any:
    with model_path.open("rb") as source:
        payload = pickle.load(source)
    estimator = payload["model"]
    if estimator.n_features_in_ != FEATURE_COUNT:
        raise ValueError(f"{model_path} expects {estimator.n_features_in_} features")
    return estimator


def select_support_vectors(estimator: Any) -> np.ndarray:
    support_vectors = estimator.support_vectors_.astype(np.float32)
    predicted_labels = estimator.predict(support_vectors)
    selected: list[np.ndarray] = []
    for class_id in estimator.classes_:
        candidates = support_vectors[predicted_labels == class_id]
        count = min(SUPPORT_VECTORS_PER_CLASS, len(candidates))
        indices = np.linspace(0, len(candidates) - 1, count, dtype=np.int64)
        selected.extend(candidates[indices])
    return np.stack(selected).astype(np.float32)


def deterministic_stress_vectors() -> np.ndarray:
    y, x = np.indices((FEATURE_HEIGHT, FEATURE_WIDTH))
    vectors = [
        np.zeros((FEATURE_HEIGHT, FEATURE_WIDTH), dtype=np.float32),
        np.full((FEATURE_HEIGHT, FEATURE_WIDTH), 255, dtype=np.float32),
        np.broadcast_to(np.linspace(0, 255, FEATURE_WIDTH), (FEATURE_HEIGHT, FEATURE_WIDTH)),
        np.broadcast_to(
            np.linspace(0, 255, FEATURE_HEIGHT)[:, np.newaxis],
            (FEATURE_HEIGHT, FEATURE_WIDTH),
        ),
        ((x + y) % 2 * 255).astype(np.float32),
        (((x // 4) + (y // 4)) % 2 * 255).astype(np.float32),
        ((x % 7) < 2).astype(np.float32) * 255,
        ((y % 9) < 3).astype(np.float32) * 255,
    ]
    rng = np.random.default_rng(0x5EED018)
    for probability in np.linspace(0.1, 0.8, 8):
        vectors.append((rng.random((FEATURE_HEIGHT, FEATURE_WIDTH)) < probability) * 255)
    for _ in range(8):
        vectors.append(rng.integers(0, 256, (FEATURE_HEIGHT, FEATURE_WIDTH)))
    return np.stack(vectors).reshape(-1, FEATURE_COUNT).astype(np.float32)


def sklearn_outputs(estimator: Any, vectors: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    labels = estimator.predict(vectors).astype(np.int64)
    scores = np.asarray(estimator.decision_function(vectors), dtype=np.float64)
    if scores.ndim == 1:
        scores = scores[:, np.newaxis]
    return labels, scores


def onnx_metrics(
    ort: Any,
    onnx_path: Path,
    vectors: np.ndarray,
    expected_labels: np.ndarray,
    expected_scores: np.ndarray,
) -> tuple[dict[str, Any], np.ndarray]:
    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    actual_labels, actual_scores = session.run(None, {"input": vectors})
    label_mismatches = int(np.count_nonzero(actual_labels != expected_labels))
    comparable_scores = actual_scores[:, :1] if expected_scores.shape[1] == 1 else actual_scores
    max_score_delta = float(np.max(np.abs(comparable_scores - expected_scores)))
    inverse_delta = None
    if expected_scores.shape[1] == 1:
        inverse_delta = float(np.max(np.abs(actual_scores[:, 1:2] + expected_scores)))
    if label_mismatches:
        raise RuntimeError(f"{onnx_path.name}: {label_mismatches} label mismatches")
    metrics = {
        "labelMismatches": label_mismatches,
        "maxAbsoluteScoreDelta": max_score_delta,
        "binaryInverseMaxAbsoluteScoreDelta": inverse_delta,
    }
    return metrics, actual_scores.astype(np.float32)


def write_fixture(
    output_path: Path,
    vectors: np.ndarray,
    labels: np.ndarray,
    sklearn_scores: np.ndarray,
    onnx_scores: np.ndarray,
) -> str:
    with output_path.open("wb") as raw_target:
        with gzip.GzipFile(fileobj=raw_target, mode="wb", compresslevel=9, mtime=0) as target:
            target.write(FIXTURE_MAGIC)
            target.write(
                struct.pack(
                    ">iiiii",
                    FIXTURE_VERSION,
                    FEATURE_COUNT,
                    sklearn_scores.shape[1],
                    onnx_scores.shape[1],
                    len(vectors),
                )
            )
            for vector, label, sklearn_score, onnx_score in zip(
                vectors,
                labels,
                sklearn_scores,
                onnx_scores,
            ):
                target.write(struct.pack(">i", int(label)))
                target.write(vector.astype(">f4", copy=False).tobytes())
                target.write(sklearn_score.astype(">f8", copy=False).tobytes())
                target.write(onnx_score.astype(">f4", copy=False).tobytes())
    return hashlib.sha256(output_path.read_bytes()).hexdigest()


def generate(args: argparse.Namespace) -> None:
    sklearn_version = importlib.metadata.version("scikit-learn")
    if sklearn_version != "1.2.0":
        raise RuntimeError(f"Expected scikit-learn 1.2.0, found {sklearn_version}")
    ort = None
    if args.onnx_root:
        import onnxruntime

        ort = onnxruntime
    args.output.mkdir(parents=True, exist_ok=True)
    manifest: dict[str, Any] = {
        "fixtureVersion": FIXTURE_VERSION,
        "sklearnVersion": sklearn_version,
        "featureShape": [FEATURE_HEIGHT, FEATURE_WIDTH],
        "supportVectorsPerPredictedClass": SUPPORT_VECTORS_PER_CLASS,
        "models": [],
    }
    stress_vectors = deterministic_stress_vectors()
    for config in MODELS:
        model_path = args.model_root / f"{config.source_name}.model"
        estimator = load_estimator(model_path)
        vectors = np.concatenate((select_support_vectors(estimator), stress_vectors))
        labels, scores = sklearn_outputs(estimator, vectors)
        if ort is None or args.onnx_root is None:
            raise RuntimeError("--onnx-root is required for Android parity fixtures")
        desktop_metrics, onnx_scores = onnx_metrics(
            ort,
            args.onnx_root / config.onnx_name,
            vectors,
            labels,
            scores,
        )
        fixture_path = args.output / config.fixture_name
        fixture_hash = write_fixture(
            fixture_path,
            vectors,
            labels,
            scores,
            onnx_scores,
        )
        entry: dict[str, Any] = {
            "model": config.source_name,
            "sourceSha256": hashlib.sha256(model_path.read_bytes()).hexdigest(),
            "fixture": config.fixture_name,
            "fixtureSha256": fixture_hash,
            "vectorCount": len(vectors),
            "sklearnScoreCount": scores.shape[1],
            "onnxScoreCount": onnx_scores.shape[1],
            "labelCounts": {
                str(class_id): int(np.count_nonzero(labels == class_id))
                for class_id in estimator.classes_
            },
        }
        entry["desktopOnnx"] = desktop_metrics
        manifest["models"].append(entry)
    manifest_path = args.output / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    generate(parse_args())
