#!/usr/bin/env python3
"""Evaluate one SheetSight OMR debug bundle against a COMPLETE source fixture.

The evaluator never reads production OMR output while constructing ground truth.
It consumes a completed fixture and a later portable debug bundle, matches within
annotated system/staff identities, and emits JSON with null for inapplicable or
unavailable metrics.
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import math
import re
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


@dataclass(frozen=True)
class Point:
    x: int
    y: int
    group: int | None
    track: int | None
    label: str
    tolerance: float


class Bundle:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.archive = zipfile.ZipFile(path) if path.is_file() else None

    def read_text(self, name: str) -> str:
        if self.archive is not None:
            return self.archive.read(name).decode("utf-8")
        return (self.path / name).read_text(encoding="utf-8")

    def close(self) -> None:
        if self.archive is not None:
            self.archive.close()


def tsv(bundle: Bundle, name: str) -> list[dict[str, str]]:
    return list(csv.DictReader(io.StringIO(bundle.read_text(name)), delimiter="\t"))


def int_or_none(value: str | None) -> int | None:
    return int(value) if value not in (None, "", "null") else None


def float_or_none(value: str | None) -> float | None:
    return float(value) if value not in (None, "") else None


def report_int(report: str, key: str) -> int | None:
    match = re.search(rf"(?:^|[, ]){re.escape(key)}=(\d+)", report)
    return int(match.group(1)) if match else None


def hungarian(costs: list[list[float]]) -> list[int]:
    """Minimum-cost square assignment; deterministic lowest-column tie break."""
    size = len(costs)
    if any(len(row) != size for row in costs):
        raise ValueError("Hungarian cost matrix must be square")
    row_potential = [0.0] * (size + 1)
    column_potential = [0.0] * (size + 1)
    row_for_column = [0] * (size + 1)
    previous_column = [0] * (size + 1)
    for row in range(1, size + 1):
        row_for_column[0] = row
        current_column = 0
        minimum_reduced_cost = [math.inf] * (size + 1)
        used = [False] * (size + 1)
        while True:
            used[current_column] = True
            current_row = row_for_column[current_column]
            delta = math.inf
            next_column = 0
            for column in range(1, size + 1):
                if used[column]:
                    continue
                reduced = (
                    costs[current_row - 1][column - 1]
                    - row_potential[current_row]
                    - column_potential[column]
                )
                if reduced < minimum_reduced_cost[column]:
                    minimum_reduced_cost[column] = reduced
                    previous_column[column] = current_column
                if minimum_reduced_cost[column] < delta:
                    delta = minimum_reduced_cost[column]
                    next_column = column
            for column in range(size + 1):
                if used[column]:
                    row_potential[row_for_column[column]] += delta
                    column_potential[column] -= delta
                else:
                    minimum_reduced_cost[column] -= delta
            current_column = next_column
            if row_for_column[current_column] == 0:
                break
        while True:
            next_column = previous_column[current_column]
            row_for_column[current_column] = row_for_column[next_column]
            current_column = next_column
            if current_column == 0:
                break
    assignment = [-1] * size
    for column in range(1, size + 1):
        row = row_for_column[column]
        if row:
            assignment[row - 1] = column - 1
    return assignment


def match_points(expected: list[Point], actual: list[Point], x_only: bool = False) -> list[tuple[int, int]]:
    if not expected or not actual:
        return []
    size = len(expected) + len(actual)
    maximum_distance = max(point.tolerance for point in expected)
    unmatched_penalty = (size + 1) * (maximum_distance + 1.0)
    forbidden_cost = unmatched_penalty * (size + 1) * 4.0
    costs: list[list[float]] = []
    for row in range(size):
        cost_row: list[float] = []
        for column in range(size):
            if row < len(expected) and column < len(actual):
                reference = expected[row]
                detected = actual[column]
                same_group = reference.group is None or reference.group == detected.group
                same_track = reference.track is None or reference.track == detected.track
                distance = abs(reference.x - detected.x) if x_only else math.hypot(
                    reference.x - detected.x, reference.y - detected.y
                )
                cost_row.append(
                    distance
                    if same_group and same_track and distance <= reference.tolerance
                    else forbidden_cost
                )
            elif row < len(expected) or column < len(actual):
                cost_row.append(unmatched_penalty)
            else:
                cost_row.append(0.0)
        costs.append(cost_row)
    assignment = hungarian(costs)
    return [
        (expected_index, actual_index)
        for expected_index, actual_index in enumerate(assignment[: len(expected)])
        if actual_index < len(actual) and costs[expected_index][actual_index] < forbidden_cost
    ]


def detection_metrics(expected_count: int, actual_count: int, matches: int) -> dict[str, Any]:
    false_positives = actual_count - matches
    false_negatives = expected_count - matches
    precision = matches / actual_count if actual_count else None
    recall = matches / expected_count if expected_count else None
    f1 = (
        2.0 * precision * recall / (precision + recall)
        if precision is not None and recall is not None and precision + recall
        else None
    )
    return {
        "truePositives": matches,
        "falsePositives": false_positives,
        "falseNegatives": false_negatives,
        "precision": precision,
        "recall": recall,
        "f1": f1,
    }


def accuracy(correct: int, total: int) -> float | None:
    return correct / total if total else None


def evidence_fields(value: str) -> dict[str, str]:
    fields: dict[str, str] = {}
    for item in value.split(";"):
        if "=" in item:
            key, field_value = item.split("=", 1)
            fields[key] = field_value
    return fields


def parse_pitch(row: dict[str, str]) -> tuple[str, int, int] | None:
    match = re.fullmatch(r"([A-G])(-?\d+)", row.get("finalPitch", ""))
    if not match:
        return None
    alteration = {"FLAT": -1, "NATURAL": 0, "SHARP": 1}.get(row.get("accidental", ""))
    return (match.group(1), int(match.group(2)), alteration) if alteration is not None else None


def evaluate(fixture_path: Path, bundle_path: Path) -> dict[str, Any]:
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
    if fixture.get("annotationStatus") != "COMPLETE" or not fixture.get("eligibleForMetrics"):
        raise ValueError(f"Fixture is not metric-eligible: {fixture_path}")
    bundle = Bundle(bundle_path)
    try:
        report = bundle.read_text("accuracy-report.txt")
        resolution = re.search(r"canonicalResolution=(\d+)x(\d+)", report)
        if not resolution:
            raise ValueError("accuracy-report.txt has no canonical resolution")
        canonical_width, canonical_height = map(int, resolution.groups())
        source_width = fixture["source"]["width"]
        source_height = fixture["source"]["height"]
        scale_x = canonical_width / source_width
        scale_y = canonical_height / source_height
        tolerance_spaces = fixture["matchingToleranceStaffSpaces"]

        staff_by_identity: dict[tuple[int, int], dict[str, Any]] = {}
        system_by_index: dict[int, dict[str, Any]] = {}
        for system in fixture["systems"]:
            system_by_index[system["index"]] = system
            for staff in system["staffs"]:
                staff_by_identity[(system["index"], staff["track"])] = staff

        def tolerance(system: int, track: int) -> float:
            lines = staff_by_identity[(system, track)]["lineYsAtCenterX"]
            spacing = sum(b - a for a, b in zip(lines, lines[1:])) / (len(lines) - 1)
            return spacing * scale_y * tolerance_spaces

        expected_notes_raw = [event for event in fixture["events"] if event["kind"] == "NOTEHEAD"]
        expected_rests_raw = [event for event in fixture["events"] if event["kind"] == "REST"]
        expected_notes = [
            Point(
                round(event["x"] * scale_x),
                round(event["y"] * scale_y),
                event["system"],
                event["staff"],
                event["head"],
                tolerance(event["system"], event["staff"]),
            )
            for event in expected_notes_raw
        ]
        expected_rests = [
            Point(
                round(event["x"] * scale_x),
                round(event["y"] * scale_y),
                event["system"],
                event["staff"],
                event["restType"],
                tolerance(event["system"], event["staff"]),
            )
            for event in expected_rests_raw
        ]

        note_rows = tsv(bundle, "detections/noteheads.tsv")
        actual_notes = [
            Point(
                int(row["x"]), int(row["y"]), int_or_none(row["group"]),
                int_or_none(row["track"]), row["label"], 0.0
            )
            for row in note_rows
        ]
        rest_rows = tsv(bundle, "detections/rests.tsv")
        actual_rests = [
            Point(
                int(row["x"]), int(row["y"]), int_or_none(row["group"]),
                int_or_none(row["track"]), row["label"], 0.0
            )
            for row in rest_rows
        ]
        note_matches = match_points(expected_notes, actual_notes)
        rest_matches = match_points(expected_rests, actual_rests)

        head_map = {"FILLED": "SOLID", "OPEN": "HALF_OR_WHOLE"}
        head_correct = sum(
            head_map[expected_notes[expected_index].label] == actual_notes[actual_index].label
            for expected_index, actual_index in note_matches
        )
        rest_type_correct = sum(
            expected_rests[expected_index].label == actual_rests[actual_index].label
            for expected_index, actual_index in rest_matches
        )

        interpreted_rows = tsv(bundle, "detections/interpreted-events.tsv")
        interpreted_note_rows = [row for row in interpreted_rows if row["kind"] == "NOTE"]
        interpreted_rest_rows = [row for row in interpreted_rows if row["kind"] == "REST"]
        interpreted_notes = [
            Point(
                int(row["x"]), int(row["y"]), int(row["group"]), int(row["track"]),
                row["eventId"], 0.0
            )
            for row in interpreted_note_rows
        ]
        interpreted_rests = [
            Point(
                int(row["x"]), int(row["y"]), int(row["group"]), int(row["track"]),
                row["eventId"], 0.0
            )
            for row in interpreted_rest_rows
        ]
        interpreted_note_matches = match_points(expected_notes, interpreted_notes)
        interpreted_rest_matches = match_points(expected_rests, interpreted_rests)
        note_row_by_expected = {
            expected_index: interpreted_note_rows[actual_index]
            for expected_index, actual_index in interpreted_note_matches
        }
        rest_row_by_expected = {
            expected_index: interpreted_rest_rows[actual_index]
            for expected_index, actual_index in interpreted_rest_matches
        }

        pitch_step_correct = octave_correct = full_pitch_correct = 0
        stem_correct = beams_correct = flags_correct = dots_correct = 0
        for expected_index, row in note_row_by_expected.items():
            expected = expected_notes_raw[expected_index]
            parsed = parse_pitch(row)
            if parsed is not None:
                step, octave, alteration = parsed
                pitch_step_correct += step == expected["pitch"]["step"]
                octave_correct += octave == expected["pitch"]["octave"]
                full_pitch_correct += (
                    step == expected["pitch"]["step"]
                    and octave == expected["pitch"]["octave"]
                    and alteration == expected["pitch"].get("alter", 0)
                )
            evidence = evidence_fields(row["evidence"])
            stem_correct += evidence.get("stem") == expected["stem"]
            beams_correct += int_or_none(evidence.get("beam")) == expected["beams"]
            flags_correct += int_or_none(evidence.get("flag")) == expected["flags"]
            dots_correct += int_or_none(evidence.get("dots")) == expected["dots"]

        expected_event_groups: dict[str, list[int]] = {}
        for index, event in enumerate(expected_notes_raw):
            expected_event_groups.setdefault(event["chordId"], []).append(index)
        duration_correct = 0
        for member_indices in expected_event_groups.values():
            rows = [note_row_by_expected.get(index) for index in member_indices]
            expected_duration = expected_notes_raw[member_indices[0]]["duration"]
            if all(row is not None for row in rows):
                duration_correct += all(
                    int_or_none(row["durationNumerator"]) == expected_duration["numerator"]
                    and int_or_none(row["durationDenominator"]) == expected_duration["denominator"]
                    for row in rows if row is not None
                )
        for expected_index, expected in enumerate(expected_rests_raw):
            row = rest_row_by_expected.get(expected_index)
            if row is not None:
                duration_correct += (
                    int_or_none(row["durationNumerator"]) == expected["duration"]["numerator"]
                    and int_or_none(row["durationDenominator"]) == expected["duration"]["denominator"]
                )
        duration_total = len(expected_event_groups) + len(expected_rests_raw)

        chord_groups = [indices for indices in expected_event_groups.values() if len(indices) > 1]
        owner_available = bool(interpreted_note_rows) and "ownerEventId" in interpreted_note_rows[0]
        chord_exact_correct = 0
        if owner_available:
            actual_members_by_owner: dict[str, set[int]] = {}
            for actual_index, row in enumerate(interpreted_note_rows):
                actual_members_by_owner.setdefault(row["ownerEventId"], set()).add(actual_index)
            expected_to_actual = dict(interpreted_note_matches)
            for members in chord_groups:
                actual_members = [expected_to_actual.get(index) for index in members]
                if any(index is None for index in actual_members):
                    continue
                owners = {interpreted_note_rows[index]["ownerEventId"] for index in actual_members if index is not None}
                if len(owners) == 1 and actual_members_by_owner[next(iter(owners))] == set(actual_members):
                    chord_exact_correct += 1

        rhythmically_valid_measures: int | None = None
        measure_available = bool(interpreted_rows) and "measureIndex" in interpreted_rows[0]
        if owner_available and measure_available:
            measure_valid = {
                index: True for index in range(fixture["expected"]["measureCount"])
            }
            matched_note_actuals = {actual for _, actual in interpreted_note_matches}
            matched_rest_actuals = {actual for _, actual in interpreted_rest_matches}
            expected_to_note_actual = dict(interpreted_note_matches)

            for member_indices in expected_event_groups.values():
                expected = expected_notes_raw[member_indices[0]]
                measure = expected["measure"]
                actual_indices = [expected_to_note_actual.get(index) for index in member_indices]
                rows = [
                    interpreted_note_rows[index]
                    for index in actual_indices
                    if index is not None
                ]
                duration = expected["duration"]
                owners = {row["ownerEventId"] for row in rows if row["ownerEventId"]}
                exact_owner_members = (
                    len(owners) == 1
                    and actual_members_by_owner[next(iter(owners))]
                    == {index for index in actual_indices if index is not None}
                )
                measure_valid[measure] &= (
                    len(rows) == len(member_indices)
                    and all(int_or_none(row["measureIndex"]) == measure for row in rows)
                    and all(
                        int_or_none(row["durationNumerator"]) == duration["numerator"]
                        and int_or_none(row["durationDenominator"]) == duration["denominator"]
                        for row in rows
                    )
                    and exact_owner_members
                )

            for expected_index, expected in enumerate(expected_rests_raw):
                measure = expected["measure"]
                row = rest_row_by_expected.get(expected_index)
                duration = expected["duration"]
                measure_valid[measure] &= (
                    row is not None
                    and int_or_none(row["measureIndex"]) == measure
                    and int_or_none(row["durationNumerator"]) == duration["numerator"]
                    and int_or_none(row["durationDenominator"]) == duration["denominator"]
                )

            for actual_index, row in enumerate(interpreted_note_rows):
                if actual_index in matched_note_actuals:
                    continue
                measure = int_or_none(row["measureIndex"])
                if measure in measure_valid:
                    measure_valid[measure] = False
            for actual_index, row in enumerate(interpreted_rest_rows):
                if actual_index in matched_rest_actuals:
                    continue
                measure = int_or_none(row["measureIndex"])
                if measure in measure_valid:
                    measure_valid[measure] = False

            rhythmically_valid_measures = sum(measure_valid.values())

        expected_barlines: list[Point] = []
        edge_by_group: dict[int, tuple[int, int, float]] = {}
        for system in fixture["systems"]:
            group = system["index"]
            system_tolerance = tolerance(group, system["staffs"][0]["track"])
            scaled = [round(x * scale_x) for x in system["barlineXs"]]
            edge_by_group[group] = (scaled[0], scaled[-1], system_tolerance)
            expected_barlines.extend(
                Point(x, 0, group, None, "barline", system_tolerance) for x in scaled[1:-1]
            )
        raw_barlines = tsv(bundle, "detections/barlines.tsv")
        actual_barlines: list[Point] = []
        for row in raw_barlines:
            group = int(row["group"])
            x = int(row["x"])
            left, right, system_tolerance = edge_by_group[group]
            if abs(x - left) <= system_tolerance or abs(x - right) <= system_tolerance:
                continue
            actual_barlines.append(Point(x, 0, group, None, row["label"], 0.0))
        barline_matches = match_points(expected_barlines, actual_barlines, x_only=True)

        timings = tsv(bundle, "timings.tsv")
        peak_java = max(int(row["javaUsedMb"]) for row in timings)
        peak_native = max(int(row["nativeUsedMb"]) for row in timings)
        stage_sum_ms = sum(int(row["durationMs"]) for row in timings)

        local_accidentals = [event for event in expected_notes_raw if event.get("accidental") is not None]
        result = {
            "pageId": fixture["pageId"],
            "profile": {
                "canonicalResolution": f"{canonical_width}x{canonical_height}",
                "targetPixels": canonical_width * canonical_height,
            },
            "staff": {
                "expectedSystems": fixture["expected"]["systemCount"],
                "actualSystems": report_int(report, "staffSystemCount"),
                "expectedCount": fixture["expected"]["staffCount"],
                "actualCount": report_int(report, "staffCount"),
            },
            "barlineInternal": detection_metrics(
                len(expected_barlines), len(actual_barlines), len(barline_matches)
            ),
            "measure": {
                "expectedCount": fixture["expected"]["measureCount"],
                "actualCount": report_int(report, "musicXmlMeasureCount"),
                "correctCount": report_int(report, "musicXmlMeasureCount") == fixture["expected"]["measureCount"],
            },
            "notehead": detection_metrics(len(expected_notes), len(actual_notes), len(note_matches)),
            "filledOpenAccuracy": accuracy(head_correct, len(note_matches)),
            "pitchStepAccuracy": accuracy(pitch_step_correct, len(interpreted_note_matches)),
            "octaveAccuracy": accuracy(octave_correct, len(interpreted_note_matches)),
            "fullPitchAccuracy": accuracy(full_pitch_correct, len(interpreted_note_matches)),
            "durationAccuracy": accuracy(duration_correct, duration_total),
            "chordExactMatchAccuracy": (
                accuracy(chord_exact_correct, len(chord_groups)) if owner_available else None
            ),
            "rest": detection_metrics(len(expected_rests), len(actual_rests), len(rest_matches)),
            "restTypeAccuracy": accuracy(rest_type_correct, len(rest_matches)),
            "accidentalDetectionAccuracy": None if not local_accidentals else 0.0,
            "accidentalTypeAccuracy": None if not local_accidentals else 0.0,
            "stemDirectionAccuracy": accuracy(stem_correct, len(interpreted_note_matches)),
            "beamCountAccuracy": accuracy(beams_correct, len(interpreted_note_matches)),
            "flagCountAccuracy": accuracy(flags_correct, len(interpreted_note_matches)),
            "dotCountAccuracy": accuracy(dots_correct, len(interpreted_note_matches)),
            "rhythmicallyValidMeasures": rhythmically_valid_measures,
            "unresolvedEventCount": report_int(report, "unresolvedSemanticEventCount"),
            "hallucinatedEventCount": (
                len(actual_notes) - len(note_matches) + len(actual_rests) - len(rest_matches)
            ),
            "performance": {
                "stageSumMs": stage_sum_ms,
                "peakJavaUsedMb": peak_java,
                "peakNativeUsedMb": peak_native,
                "stages": {row["stage"]: int(row["durationMs"]) for row in timings},
            },
        }
        return result
    finally:
        bundle.close()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("fixture", type=Path)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--output", type=Path)
    arguments = parser.parse_args()
    result = evaluate(arguments.fixture, arguments.bundle)
    rendered = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if arguments.output:
        arguments.output.write_text(rendered, encoding="utf-8")
    else:
        print(rendered, end="")


if __name__ == "__main__":
    main()
