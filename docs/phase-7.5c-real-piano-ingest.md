# Phase 7.5C — capture ingest and verification gate

Date: 2026-08-16  
Status: integrity review complete; audible/manual verification and acoustic benchmark blocked; production recognition unchanged

## Decision

There is no candidate winner and no justification for changing production recognition. Neither attached archive supplies a benchmark-eligible row:

- The seven-case pilot ZIP is structurally intact, but every row is intentionally unverified, this execution has no audible playback/review channel, and the old exporter omitted routed-input-device and AudioRecord-buffer provenance from `manifest.tsv`.
- The larger full-matrix ZIP is truncated and has no central directory, `manifest.tsv`, or `README.txt`. Thirty complete WAVs and one partial WAV could be recovered from local ZIP records for integrity triage only. They are not dataset rows and must not enter metrics.

The pilot names a **Casio CT-X870IN**, so it is microphone evidence from a digital keyboard/speaker setup, not acoustic-piano evidence. It must not be described as an acoustic-piano benchmark.

## Original archive integrity

The originals in `C:\Users\Anugrah\Downloads` were read only. SHA-256 hashes were checked before local ignored copies were inspected.

| Archive | SHA-256 | Result |
|---|---|---|
| `sheetsight-piano-captures-1786877354733.zip` | `E68C67D8043BAFB3EDB9775179A837FA85EBB470730D74DD4AF44DF42C2FEF03` | Valid ZIP; 7 WAVs + `manifest.tsv` + `README.txt`; paths relative and unique. |
| `sheetsight-piano-captures-1786878593132.zip` | `8564AD95CB02A204D95D3A73C25BBE66050B4A8A1C764E7011DDAF190D405105` | Invalid/truncated ZIP; no end-of-central-directory record. Only 31 WAV local headers were present versus the expected 69 captures; `normal-mid-2.wav` is truncated and the remaining 38 captures are absent. No manifest was recoverable. |

### Pilot WAV integrity

- Capture count: 7 expected, 7 present, 7 manifest rows.
- IDs and WAV paths: unique; manifest and ZIP WAV sets match.
- Paths: all remain below `local-recordings/`.
- Format: all 7 are mono little-endian PCM16 at explicitly recorded 22,050 Hz.
- Length: isolated/noise/silence clips are 4.000 s; repeat/sustain clips are 5.000 s.
- Empty/corrupt/truncated: none.
- PCM clipping: zero full-scale samples in every clip. Peaks range from 106 to 21,474 on the signed PCM16 scale.
- Resampling: not needed; no derivative was created.
- Raw verification state: all rows remain `manual_verified=false`, with blank reviewers and onset labels.

The manifest otherwise reports complete device/source fields, but it cannot satisfy the Phase 7.5C complete-provenance gate because the pre-fix guided exporter omitted `routed_audio_device` and `buffer_size_frames`.

### Truncated full archive

- Expected captures: 69.
- Local WAV records found: 31.
- Complete recovered WAVs: 30; all are mono PCM16, 22,050 Hz, non-empty, and contain zero full-scale samples.
- Rejected partial WAV: `normal-mid-2` declares 88,200 frames but only 68,410 complete frames plus one trailing byte were recoverable (about 3.102 s versus 4.000 s).
- Missing capture WAVs: 38.
- Dataset metadata: absent. Prompt, intended performance, placement, room, source, device, piano, timestamps, verification state, and reviewer cannot be established from the archive.

Recovered WAVs remain local and ignored. They are not treated as verified or as real-piano evidence.

## Verification worksheet

A 38-row local worksheet was created at:

`tools/practice-audio/local-recordings/phase-7.5c/verification-worksheet.tsv`

It contains the requested recording/scenario/expected/intended/audible/onset/noise/clipping/decision/reviewer fields, plus clearly labeled DSP review aids. DSP candidates are not manual or audible ground truth.

Pilot decisions:

| ID | Decision | Provisional DSP review aid (not ground truth) |
|---|---|---|
| `pilot-normal-c4` | Needs user confirmation | Strong C4 evidence begins near 1,033 ms; a separate early transient appears near 29 ms. |
| `pilot-very-soft-c4` | Needs user confirmation | Strongest pitch candidate is C4; first attack candidate near 400 ms. |
| `pilot-wrong-d4` | Needs user confirmation | Strongest pitch candidate is D4; first attack candidate near 127 ms. |
| `pilot-restrikes-c4` | Needs user confirmation | Three C4 attack candidates near 859, 2,304, and 3,831 ms. |
| `pilot-sustain-c4` | Needs user confirmation | One C4 attack candidate near 238 ms; later tail fluctuations were not promoted to attacks. |
| `pilot-room-noise` | Needs user confirmation | No stable piano pitch established; broadband/low-frequency events appear near 2,542 and 3,604 ms. |
| `pilot-silence` | Needs user confirmation | Peak is only 106/32,768; no stable piano pitch established. |

All 30 complete full-archive WAVs are rejected as dataset rows because `manifest.tsv` and provenance are absent. `normal-mid-2` is additionally rejected as truncated.

## Represented setup

Only the valid pilot has trustworthy provenance:

- Device: OnePlus CPH2707.
- Android: 16 (API 36).
- Piano field: Casio CT-X870IN (digital keyboard; not an acoustic piano).
- Room: `Quiet room`.
- Placement: `NORMAL` only.
- Requested/actual audio source: `DEFAULT` / `DEFAULT`.
- Requested/actual sample rate: 22,050 / 22,050 Hz.
- UNPROCESSED advertised: true, but not requested for these takes.
- AGC observation: `UNAVAILABLE`.
- Noise suppressor observation: `AVAILABLE_DISABLED`.
- Routed device and AudioRecord buffer frames: missing from this export.

These Android effect states are observable API state, not proof that the OEM applied no hidden DSP. No buffer value is reported as end-to-end latency.

## A/B/C/D/F acoustic metrics

No acoustic metric was computed. The verified-corpus size is zero, and the enforced loader gate must reject every attached row.

| Candidate | Recall and dynamic/register slices | Wrong/semitone/octave/repeat/residual/silence/noise safety | Median/P95 latency | Android cost |
|---|---|---|---|---|
| A fixed-RMS YIN | N/A — 0 verified rows | N/A | N/A | No replay result in ZIP |
| B adaptive YIN | N/A — 0 verified rows | N/A | N/A | No replay result in ZIP |
| C harmonic + independent re-arm | N/A — 0 verified rows | N/A | N/A | No replay result in ZIP |
| D log-frequency + independent re-arm | N/A — 0 verified rows | N/A | N/A | No replay result in ZIP |
| F hybrid + independent re-arm | N/A — 0 verified rows | N/A | N/A | No replay result in ZIP |

Historical synthetic figures in the Phase 7.5A/7.5B documents remain synthetic regression evidence only and were not relabeled as results from these recordings.

No Android replay JSON was included. Cold initialization, processing per hop, sustained CPU, PSS, real-time factor, APK size, battery, and thermal observations therefore remain unmeasured for this corpus.

The current debug APK assembled successfully at 420,709,456 bytes. This is the whole debug APK, not a candidate-specific size delta and not an Android replay cost measurement.

## Engineering changes made in Phase 7.5C

- Added a strict debug-only capture-bundle validator for central-directory validity, safe/unique paths, exact manifest/WAV correspondence, expected capture count, unique IDs, raw `manual_verified=false`, complete provenance, mono PCM16 parsing, explicit sample-rate agreement, and clipping rejection.
- Added duplicate-ID/WAV-path checks to the reviewed-manifest reader.
- Fixed the guided exporter so future manifests retain routed input device and AudioRecord buffer frames already collected by the recorder.
- Added a read-only DSP inspection aid which explicitly labels its output as not manual ground truth.
- Added regression tests for valid bundle export, truncated ZIP rejection, path traversal rejection, duplicate IDs, verified-manifest provenance, and truncated WAV rejection. Existing synthetic tests continue to cover wrong notes, neighboring semitones, octave errors, repeated restrikes, sustained repeats, residuals, silence/noise, and exact-once advancement.

## Winner, production integration, and next action

- Clear winner: **no**.
- Production integration justified: **no**.
- Production detector changed: **no**; adaptive YIN remains in place.
- PracticeEngine/UI/timing/articulation/release ownership changed: **no**.

## Files and verification run

Created by Phase 7.5C:

- `app/src/debug/java/com/sheetsight/app/data/audio/benchmark/BenchmarkDatasetBundleValidator.kt`
- `tools/practice-audio/inspect_recordings.py`
- `docs/phase-7.5c-real-piano-ingest.md`
- Ignored local archive copies, recovered WAVs, `dsp-inspection.json`, and `verification-worksheet.tsv` under `tools/practice-audio/local-recordings/phase-7.5c/`

Modified by Phase 7.5C:

- `app/src/main/java/com/sheetsight/app/data/audio/DeveloperPianoCapture.kt`
- `app/src/debug/java/com/sheetsight/app/data/audio/benchmark/PracticeRecognitionBenchmark.kt`
- `app/src/test/java/com/sheetsight/app/data/audio/DeveloperPianoCaptureTest.kt`
- `app/src/test/java/com/sheetsight/app/data/audio/benchmark/PracticeRecognitionBenchmarkTest.kt`
- `tools/practice-audio/dataset-template.tsv`
- `tools/practice-audio/README.md`
- `README.md`

Tests/builds actually run:

- Focused `DeveloperPianoCaptureTest` + `PracticeRecognitionBenchmarkTest`: passed.
- Complete `testDebugUnitTest`: 339 tests in 62 suites; 0 failures, 0 errors, 0 skipped.
- `compileDebugAndroidTestKotlin`: passed.
- `assembleDebug`: passed.
- Read-only Python inspection-tool parse check: passed.
- Android replay/capture instrumented execution: not run; no verified replay corpus exists.

Exact next action: install the build containing the provenance-export fix, then repeat the seven-case pilot on an actual acoustic piano with `UNPROCESSED_PREFERRED`. Keep the capture screen open until export reports completion, verify the resulting ZIP can be reopened, and attach it. Also repeat the full matrix because the current larger export is irrecoverably incomplete. The seven pilot IDs listed above need complete audible review; confirmation of their notes alone would still not repair the missing routed-device/buffer provenance in the old export.
