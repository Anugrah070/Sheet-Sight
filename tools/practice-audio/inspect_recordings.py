#!/usr/bin/env python3
"""Read-only PCM/WAV integrity and provisional onset/pitch inspection.

This tool deliberately does not mark recordings as manually verified. Its pitch and
onset estimates are review aids; a reviewer must still listen to the complete WAV.
"""

from __future__ import annotations

import argparse
import json
import math
import struct
import wave
from pathlib import Path

import numpy as np
from scipy.signal import find_peaks


def midi_frequency(midi: int) -> float:
    return 440.0 * (2.0 ** ((midi - 69) / 12.0))


def parse_declared_data_bytes(path: Path) -> int | None:
    with path.open("rb") as stream:
        header = stream.read(12)
        if len(header) != 12 or header[:4] != b"RIFF" or header[8:] != b"WAVE":
            return None
        while True:
            chunk = stream.read(8)
            if len(chunk) != 8:
                return None
            chunk_id, size = struct.unpack("<4sI", chunk)
            if chunk_id == b"data":
                return size
            stream.seek(size + (size & 1), 1)


def pitch_scores(samples: np.ndarray, sample_rate: int, center_sample: int) -> list[dict[str, float | int]]:
    window_size = min(8192, len(samples))
    start = min(max(0, center_sample), max(0, len(samples) - window_size))
    window = samples[start : start + window_size]
    if len(window) < 2048:
        return []
    window = window - np.mean(window)
    window = window * np.hanning(len(window))
    spectrum = np.abs(np.fft.rfft(window))
    frequencies = np.fft.rfftfreq(len(window), 1.0 / sample_rate)
    rms = float(np.sqrt(np.mean(window * window)))
    if rms < 1e-5:
        return []

    scored: list[tuple[int, float]] = []
    for midi in range(21, 109):
        fundamental = midi_frequency(midi)
        score = 0.0
        weight_sum = 0.0
        for harmonic in range(1, 9):
            frequency = fundamental * harmonic
            if frequency >= sample_rate / 2:
                break
            magnitude = float(np.interp(frequency, frequencies, spectrum))
            weight = 1.0 / math.sqrt(harmonic)
            score += weight * math.log1p(magnitude)
            weight_sum += weight
        score /= max(weight_sum, 1e-9)
        if midi >= 33:
            lower = float(np.interp(fundamental / 2.0, frequencies, spectrum))
            score -= 0.16 * math.log1p(lower)
        scored.append((midi, score))
    scored.sort(key=lambda value: value[1], reverse=True)
    top = scored[:3]
    floor = float(np.median([score for _, score in scored]))
    scale = max(1e-9, top[0][1] - floor)
    return [
        {"midi": midi, "score": round(score, 6), "relative": round((score - floor) / scale, 6)}
        for midi, score in top
    ]


def onset_candidates(samples: np.ndarray, sample_rate: int) -> list[int]:
    frame_size = 1024
    hop_size = 128
    if len(samples) < frame_size:
        return []
    frames = np.lib.stride_tricks.sliding_window_view(samples, frame_size)[::hop_size]
    window = np.hanning(frame_size)
    rms = np.sqrt(np.mean(frames * frames, axis=1))
    spectra = np.abs(np.fft.rfft(frames * window, axis=1))
    positive_flux = np.maximum(0.0, np.diff(spectra, axis=0)).sum(axis=1)
    positive_flux = np.concatenate(([0.0], positive_flux))
    log_rms = np.log1p(rms * 500.0)
    rms_rise = np.maximum(0.0, np.diff(log_rms, prepend=log_rms[0]))
    flux_scale = np.percentile(positive_flux, 90)
    rise_scale = np.percentile(rms_rise, 90)
    strength = positive_flux / max(flux_scale, 1e-12) + rms_rise / max(rise_scale, 1e-12)
    noise_rms = float(np.percentile(rms, 20))
    active = rms >= max(noise_rms * 2.0, 0.0008)
    strength = strength * active
    prominence = max(0.45, float(np.percentile(strength, 75)) * 0.45)
    peaks, _ = find_peaks(
        strength,
        height=max(0.8, float(np.percentile(strength, 85))),
        prominence=prominence,
        distance=max(1, int(0.24 * sample_rate / hop_size)),
    )
    return [int((int(peak) * hop_size + frame_size // 2) * 1000 / sample_rate) for peak in peaks]


def inspect_wave(path: Path) -> dict[str, object]:
    result: dict[str, object] = {"path": str(path), "name": path.name, "file_bytes": path.stat().st_size}
    try:
        declared_data_bytes = parse_declared_data_bytes(path)
        with wave.open(str(path), "rb") as wav:
            channels = wav.getnchannels()
            sample_width = wav.getsampwidth()
            sample_rate = wav.getframerate()
            declared_frames = wav.getnframes()
            pcm = wav.readframes(declared_frames)
        actual_frames = len(pcm) // max(1, channels * sample_width)
        result.update(
            channels=channels,
            sample_width_bits=sample_width * 8,
            sample_rate_hz=sample_rate,
            declared_frames=declared_frames,
            actual_frames=actual_frames,
            declared_data_bytes=declared_data_bytes,
            actual_data_bytes=len(pcm),
            duration_ms=round(actual_frames * 1000.0 / sample_rate, 3) if sample_rate else None,
        )
        format_ok = channels == 1 and sample_width == 2 and sample_rate > 0
        complete = declared_data_bytes is not None and len(pcm) == declared_data_bytes
        result["format_ok"] = format_ok
        result["complete"] = complete
        if not format_ok or not pcm:
            result["error"] = "unsupported format or empty PCM"
            return result
        samples_i16 = np.frombuffer(pcm, dtype="<i2")
        samples = samples_i16.astype(np.float64) / 32768.0
        clipped = int(np.count_nonzero((samples_i16 == 32767) | (samples_i16 == -32768)))
        result.update(
            empty=bool(samples_i16.size == 0),
            peak_abs=int(np.max(np.abs(samples_i16.astype(np.int32)))) if samples_i16.size else 0,
            clipped_samples=clipped,
            clipped_fraction=round(clipped / max(1, samples_i16.size), 9),
            rms=round(float(np.sqrt(np.mean(samples * samples))), 9),
            dc_offset=round(float(np.mean(samples)), 9),
        )
        onsets = onset_candidates(samples, sample_rate)
        result["provisional_onsets_ms"] = onsets
        result["provisional_pitch_candidates"] = [
            {
                "onset_ms": onset,
                "top": pitch_scores(samples, sample_rate, int((onset / 1000.0 + 0.045) * sample_rate)),
            }
            for onset in onsets
        ]
        return result
    except Exception as error:  # Keep inspecting the remainder of a damaged bundle.
        result.update(format_ok=False, complete=False, error=f"{type(error).__name__}: {error}")
        return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("roots", nargs="+", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    paths = sorted({path.resolve() for root in args.roots for path in root.rglob("*.wav")})
    report = {
        "evidence": "READ_ONLY_DSP_REVIEW_AID_NOT_MANUAL_GROUND_TRUTH",
        "recording_count": len(paths),
        "recordings": [inspect_wave(path) for path in paths],
    }
    encoded = json.dumps(report, indent=2) + "\n"
    if args.output:
        args.output.write_text(encoded, encoding="utf-8")
    print(encoded, end="")


if __name__ == "__main__":
    main()
