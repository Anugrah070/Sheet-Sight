#!/usr/bin/env python3
"""Debug-only Basic Pitch replay over the exact JVM-exported Phase 7.5A fixtures.

This is an independent, minimal ONNX evaluator based on Spotify's publicly
documented constants and tensor names. It does not modify or integrate the
production Android detector. The Basic Pitch repository and bundled model are
Apache-2.0: https://github.com/spotify/basic-pitch
"""

from __future__ import annotations

import argparse
import csv
import ctypes
from ctypes import wintypes
import json
import math
import os
from pathlib import Path
import time
import wave

import numpy as np
import onnxruntime as ort


SAMPLE_RATE = 22_050
FFT_HOP = 256
WINDOW_SECONDS = 2
WINDOW_SAMPLES = SAMPLE_RATE * WINDOW_SECONDS - FFT_HOP  # 43,844
OVERLAPPING_FRAMES = 30
OVERLAP_SAMPLES = OVERLAPPING_FRAMES * FFT_HOP
WINDOW_HOP = WINDOW_SAMPLES - OVERLAP_SAMPLES
ANNOTATION_FPS = SAMPLE_RATE // FFT_HOP  # upstream behavior: integer 86 fps
ONSET_THRESHOLD = 0.5
MODEL_INPUT = "serving_default_input_2:0"
MODEL_OUTPUTS = [
    "StatefulPartitionedCall:1",  # note
    "StatefulPartitionedCall:2",  # onset
    "StatefulPartitionedCall:0",  # contour
]


def read_wav(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as wav:
        if wav.getnchannels() != 1 or wav.getsampwidth() != 2 or wav.getframerate() != SAMPLE_RATE:
            raise ValueError(f"{path}: expected mono PCM16 at {SAMPLE_RATE} Hz")
        return np.frombuffer(wav.readframes(wav.getnframes()), dtype="<i2").astype(np.float32) / 32768.0


def windows(audio: np.ndarray):
    padded = np.concatenate((np.zeros(OVERLAP_SAMPLES // 2, dtype=np.float32), audio))
    for start in range(0, len(padded), WINDOW_HOP):
        frame = padded[start : start + WINDOW_SAMPLES]
        if len(frame) < WINDOW_SAMPLES:
            frame = np.pad(frame, (0, WINDOW_SAMPLES - len(frame)))
        yield frame[np.newaxis, :, np.newaxis]


def unwrap(batches: list[np.ndarray], original_samples: int) -> np.ndarray:
    output = np.concatenate(batches, axis=0)
    half_overlap = OVERLAPPING_FRAMES // 2
    output = output[:, half_overlap:-half_overlap, :]
    flattened = output.reshape(output.shape[0] * output.shape[1], output.shape[2])
    expected_windows = original_samples / WINDOW_HOP
    frames_per_window = WINDOW_SECONDS * ANNOTATION_FPS - OVERLAPPING_FRAMES
    return flattened[: int(expected_windows * frames_per_window), :]


def infer(session: ort.InferenceSession, audio: np.ndarray) -> tuple[np.ndarray, float]:
    onset_batches: list[np.ndarray] = []
    started = time.perf_counter_ns()
    for frame in windows(audio):
        _, onset, _ = session.run(MODEL_OUTPUTS, {MODEL_INPUT: frame})
        onset_batches.append(onset)
    elapsed_ms = (time.perf_counter_ns() - started) / 1e6
    return unwrap(onset_batches, len(audio)), elapsed_ms


def onset_events(onsets: np.ndarray, duration_seconds: float):
    events: list[tuple[float, int, float]] = []
    for frame_index in range(1, max(1, onsets.shape[0] - 1)):
        for note_index in np.flatnonzero(onsets[frame_index] >= ONSET_THRESHOLD):
            value = float(onsets[frame_index, note_index])
            if value >= float(onsets[frame_index - 1, note_index]) and value > float(onsets[frame_index + 1, note_index]):
                event_seconds = frame_index / ANNOTATION_FPS
                if event_seconds <= duration_seconds:
                    events.append((event_seconds, int(note_index) + 21, value))
    events.sort()
    return events


def batch_available_at(event_seconds: float) -> float:
    # Upstream uses a 43,844-sample (~1.988 s) input with 3,840 samples of left
    # context padding. A non-streaming Android port cannot emit before the
    # window containing the event has filled.
    first_real_end = (WINDOW_SAMPLES - OVERLAP_SAMPLES / 2) / SAMPLE_RATE
    hop_seconds = WINDOW_HOP / SAMPLE_RATE
    if event_seconds <= first_real_end:
        return first_real_end
    window = math.ceil((event_seconds - first_real_end) / hop_seconds)
    return first_real_end + window * hop_seconds


def parse_ints(value: str) -> list[int]:
    return [] if not value else [int(item) for item in value.split(",") if item]


def parse_onsets(value: str) -> list[int | None]:
    return [None if item == "-" else int(item) for item in value.split(",")]


def working_set_bytes() -> int | None:
    if os.name != "nt":
        return None

    class Counters(ctypes.Structure):
        _fields_ = [
            ("cb", wintypes.DWORD),
            ("PageFaultCount", wintypes.DWORD),
            ("PeakWorkingSetSize", ctypes.c_size_t),
            ("WorkingSetSize", ctypes.c_size_t),
            ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
            ("QuotaPagedPoolUsage", ctypes.c_size_t),
            ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
            ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
            ("PagefileUsage", ctypes.c_size_t),
            ("PeakPagefileUsage", ctypes.c_size_t),
        ]

    counters = Counters()
    counters.cb = ctypes.sizeof(counters)
    function = ctypes.windll.psapi.GetProcessMemoryInfo
    function.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
    function.restype = wintypes.BOOL
    ok = function(ctypes.windll.kernel32.GetCurrentProcess(), ctypes.byref(counters), counters.cb)
    return int(counters.WorkingSetSize) if ok else None


def percentile(values: list[int], q: float) -> int | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[round((len(ordered) - 1) * q)]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    init_started = time.perf_counter_ns()
    session = ort.InferenceSession(str(args.model), providers=["CPUExecutionProvider"])
    cold_init_ms = (time.perf_counter_ns() - init_started) / 1e6
    memory_after_init = working_set_bytes()

    rows = list(csv.DictReader((args.fixtures / "manifest.tsv").open(encoding="utf-8"), delimiter="\t"))
    results = []
    total_processing_ms = 0.0
    total_audio_seconds = 0.0
    all_latencies: list[int] = []
    for row in rows:
        audio = read_wav(args.fixtures / row["wav"])
        duration = len(audio) / SAMPLE_RATE
        onsets, processing_ms = infer(session, audio)
        total_processing_ms += processing_ms
        total_audio_seconds += duration
        expected = parse_ints(row["expected_midi_sequence"])
        truth = parse_onsets(row["expected_onsets_ms"])
        pointer = 0
        advances = []
        wrong = []
        for event_seconds, midi, confidence in onset_events(onsets, duration):
            if pointer >= len(expected):
                break
            if midi == expected[pointer]:
                availability_ms = round(batch_available_at(event_seconds) * 1000)
                truth_onset = truth[pointer]
                matched = truth_onset is not None and round(event_seconds * 1000) >= truth_onset - 40
                latency = availability_ms - truth_onset if matched else None
                if latency is not None:
                    all_latencies.append(latency)
                advances.append({
                    "step": pointer,
                    "event_ms": round(event_seconds * 1000),
                    "available_ms": availability_ms,
                    "confidence": confidence,
                    "matched": matched,
                    "latency_ms": latency,
                })
                pointer += 1
            else:
                wrong.append({"event_ms": round(event_seconds * 1000), "midi": midi, "confidence": confidence})
        expected_count = sum(value is not None for value in truth)
        matched_count = sum(item["matched"] for item in advances)
        results.append({
            "id": row["id"],
            "scenario": row["scenario"],
            "register": row["register"],
            "attack": row["attack"],
            "expected_count": expected_count,
            "matched_count": matched_count,
            "false_advances": len(advances) - matched_count,
            "advances": advances,
            "wrong": wrong,
            "processing_ms": processing_ms,
        })

    expected_total = sum(item["expected_count"] for item in results)
    matched_total = sum(item["matched_count"] for item in results)
    negative_steps = sum(parse_onsets(row["expected_onsets_ms"]).count(None) for row in rows)
    false_advances = sum(item["false_advances"] for item in results)
    wrong_cases = [item for item in results if item["scenario"] in {"WRONG_ISOLATED", "NEIGHBOR_SEMITONE", "OCTAVE_ERROR"}]
    soft = [item for item in results if item["attack"] in {"VERY_SOFT", "SOFT"}]
    soft_expected = sum(item["expected_count"] for item in soft)
    repeated = [item for item in results if item["scenario"] in {"REPEATED_RESTRIKES", "REPEATED_SUSTAIN"}]
    repeated_errors = sum(
        item["false_advances"] > 0 or item["matched_count"] != item["expected_count"] for item in repeated
    )
    octave = [item for item in results if item["scenario"] == "OCTAVE_ERROR"]
    report = {
        "candidate": "E-basic-pitch-onnx-default-onset-threshold",
        "provenance": "AUTHOR_DEFINED_SYNTHETIC_ONLY",
        "model_bytes": args.model.stat().st_size,
        "cold_initialization_ms": cold_init_ms,
        "working_set_after_init_bytes": memory_after_init,
        "processing_ms": total_processing_ms,
        "real_time_factor": total_processing_ms / 1000 / total_audio_seconds,
        "expected_recall": matched_total / expected_total if expected_total else None,
        "wrong_note_rejection": sum(not item["advances"] for item in wrong_cases) / len(wrong_cases),
        "false_positive_rate": false_advances / negative_steps if negative_steps else None,
        "soft_note_recall": sum(item["matched_count"] for item in soft) / soft_expected if soft_expected else None,
        "octave_confusion_rate": sum(item["false_advances"] > 0 for item in octave) / len(octave),
        "repeated_note_error_rate": repeated_errors / len(repeated),
        "median_batch_latency_ms": percentile(all_latencies, 0.50),
        "p95_batch_latency_ms": percentile(all_latencies, 0.95),
        "latency_note": "Non-streaming availability from upstream 43,844-sample windows, not model timestamp error.",
        "results": results,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key != "results"}, indent=2))


if __name__ == "__main__":
    main()
