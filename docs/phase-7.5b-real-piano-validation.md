# Phase 7.5B — real-piano / Android validation

Date: 2026-08-11  
Status: guided in-app capture/export and replay infrastructure plus repeated-restrike prototype repair complete; stopped at the physical-device/piano gate; production recognition unchanged

## Decision

There is still **no production winner**. No Android device was connected and no
physical acoustic piano or manually verified acoustic recording was available in
this execution. Therefore there are no new real-piano accuracy, Android
microphone, AGC, latency, CPU, memory, APK-delta, thermal, or battery results.

The Phase 7.5A C/D/F repeated-restrike failure is repaired in debug-only benchmark
code with independent positive spectral-flux onset evidence. On the existing 20
author-defined synthetic regression clips, C/D/F now accept all three genuine
C4 restrikes while one sustained C4 still advances only the first of three C4
score steps. These results validate benchmark mechanics only. They are not
acoustic-piano evidence and were not used to tune production recognition.

`PracticeController`/`PracticeViewModel`, `PracticeEngine`, `PitchMatcher`, score
UI, timing, articulation/release ownership, and the production adaptive YIN path
were not changed by Phase 7.5B.

## 1. Devices and pianos actually tested

- Android devices connected during this execution: **none** (`adb devices -l`
  returned no device entries).
- Acoustic pianos available during this execution: **none**.
- The README's OnePlus CPH2707 and physical-piano observations remain historical
  Phase 7.5 context; they were not repeated here.

## 2. Recordings and manually verified cases collected

- New Android microphone WAV recordings: **none**.
- Manually verified acoustic rows: **none**.
- Ordinary user practice audio persisted: **none**.

The tracked template now covers four dynamics across low/mid/high registers plus
wrong notes, neighboring semitones, octave errors, restrikes, one sustained
repeat, legato, residual harmonics, note after silence, room noise, and silence.
`New-Phase75bManifest.ps1` expands every base row to 3–5 unverified takes and
rotates initial placements. With the default three takes, it creates 69 rows.
The Settings developer section also exposes a guided seven-case pilot and the
same 69-take full matrix. Its live bar and YIN note display are preview-only;
only explicit per-prompt recording intervals enter the exported ZIP.

## 3. Audio source and DSP observations

No microphone path was observed in this execution. The new explicit capture test
records, for later device runs:

- device model and Android/API version;
- requested and actual configured sample rate;
- requested and actual configured `UNPROCESSED`/`DEFAULT` source;
- whether the device advertises unprocessed-source support;
- AGC and noise-suppressor availability and enabled state where Android exposes it;
- piano, placement, room condition, routed input device, buffer frames, and UTC time.

Those fields describe observable Android state and do not prove that an OEM has
applied no hidden DSP.

## 4–8. Candidate metrics, breakdowns, safety, and latency

### Acoustic metrics

Not available. No row may contribute until its complete WAV is manually reviewed,
`manual_verified=true`, a reviewer is named, and capture provenance is complete.
The loader enforces that gate.

### Synthetic regression after the repeated-onset repair

The following is from the same 20 deterministic Phase 7.5A fixtures (48.2 s),
rerun in this execution. It is **not** physical-piano accuracy.

| Candidate | Expected recall | Wrong rejection | False advances | Very-soft recall | Soft recall | Octave confusion | Repeat error | Residual false advances | Silence/noise false advances | Median / P95 latency |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| A fixed-RMS YIN | 72.2% (13/18) | 100% | 0% | 0% (0/3) | 60% (3/5) | 0% | 0% | 0% | 0% | 57 / 103 ms |
| B adaptive YIN | 88.9% (16/18) | 100% | 0% | 100% (3/3) | 60% (3/5) | 0% | 0% | 0% | 0% | 57 / 150 ms |
| C harmonics + spectral re-arm | 100% (18/18) | 100% | 0% | 100% | 100% | 0% | 0% | 0% | 0% | 126 / 139 ms |
| D log frequency + spectral re-arm | 100% (18/18) | 100% | 0% | 100% | 100% | 0% | 0% | 0% | 0% | 103 / 150 ms |
| F hybrid + spectral re-arm | 100% (18/18) | 100% | 0% | 100% | 100% | 0% | 0% | 0% | 0% | 80 / 148 ms |

Register recall for C/D/F was 100% in low, mid, and high synthetic slices. A was
50.0% / 78.6% / 50.0%; B was 100% / 85.7% / 100%. Dynamic recall for C/D/F was
100% in very-soft, soft, normal, and strong slices. The spectral onset signal can
only re-arm an already acoustically supported expected pitch. It cannot accept a
pitch or advance the pointer by itself.

## 9. Android CPU, memory, APK, and battery observations

No Android performance measurement was performed because no device was connected.
The new Android replay test will report per candidate:

- cold initialization;
- processing milliseconds per 1,024 input samples and real-time factor;
- process CPU time and PSS before/after;
- installed APK bytes;
- start/end battery capacity, charge counter, and instantaneous current;
- start/end thermal status where the Android API supports it.

The capture test also records `AudioRecord.bufferSizeInFrames`. That is not an
end-to-end audio-latency measurement. A physical loopback or timestamped acoustic
protocol is still required for input latency and battery/thermal runs must be long
enough to be meaningful.

Desktop debug timing observed in the focused passing run was approximately:

| Candidate | Processing per 1,024-sample-equivalent block | RTF |
|---|---:|---:|
| A | 0.766 ms | 0.0163 |
| B | 0.941 ms | 0.0201 |
| C | 15.634 ms | 0.3334 |
| D | 2.353 ms | 0.0502 |
| F | 17.098 ms | 0.3647 |

These Windows/JVM values include unoptimized debug reference code and are not
Android performance evidence.

## 10–11. Winner and production integration

- Clear winner: **no**.
- Production integration justified: **no**.
- Production detector changed: **no**.
- Feature flag added: **no**, because no candidate has passed the required
  manually verified real-piano and Android gates.

## 12. Files created or modified by Phase 7.5B

Created:

- `app/src/main/java/com/sheetsight/app/data/audio/DeveloperPianoCapture.kt`
- `app/src/main/java/com/sheetsight/app/ui/debug/GuidedPianoCaptureScreen.kt`
- `app/src/main/java/com/sheetsight/app/ui/debug/GuidedPianoCaptureViewModel.kt`
- `app/src/test/java/com/sheetsight/app/data/audio/DeveloperPianoCaptureTest.kt`
- `app/src/androidTest/java/com/sheetsight/app/data/audio/benchmark/AndroidBenchmarkCaptureInstrumentedTest.kt`
- `app/src/androidTest/java/com/sheetsight/app/data/audio/benchmark/AndroidAcousticReplayInstrumentedTest.kt`
- `tools/practice-audio/New-Phase75bManifest.ps1`
- `docs/phase-7.5b-real-piano-validation.md`

Modified:

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/sheetsight/app/ui/navigation/Destination.kt`
- `app/src/main/java/com/sheetsight/app/ui/navigation/SheetSightNavHost.kt`
- `app/src/main/java/com/sheetsight/app/ui/settings/SettingsScreen.kt`
- `app/src/main/res/values/strings.xml`
- `README.md`
- `app/src/debug/java/com/sheetsight/app/data/audio/benchmark/BenchmarkDetectors.kt`
- `app/src/debug/java/com/sheetsight/app/data/audio/benchmark/PracticeRecognitionBenchmark.kt`
- `app/src/test/java/com/sheetsight/app/data/audio/benchmark/PracticeRecognitionBenchmarkTest.kt`
- `tools/practice-audio/dataset-template.tsv`
- `tools/practice-audio/README.md`

All other dirty-worktree files pre-existed Phase 7.5B and were preserved.

## 13. Tests actually run

- `PracticeRecognitionBenchmarkTest`: 8 tests passed after the Phase 7.5B changes.
  It now explicitly asserts all three C/D/F restrikes and one-advance sustained
  repeat behavior, plus the existing wrong/octave, silence/noise, loader, WAV,
  and common-fixture coverage.
- `compileDebugAndroidTestKotlin`: passed, compiling both new instrumented tools.
- PowerShell parser validation for `New-Phase75bManifest.ps1`: passed.
- `DeveloperPianoCaptureTest`: 3 tests passed for the 69-take matrix, required
  case coverage, one-file ZIP/WAV structure, provenance, and unverified-manifest gate.
- Complete `testDebugUnitTest`: 337 tests in 62 suites, 0 failures, 0 errors,
  0 skipped after adding the guided UI.
- `compileDebugAndroidTestKotlin`: passed after the guided UI changes.
- `assembleDebug`: passed; the guided recorder is packaged in the debug APK.
- Android capture/replay instrumented tests: not run; no device was connected.

One earlier focused attempt exceeded its command time limit and one combined
verification attempt exposed a test-fixture and Kotlin type error. Both were
corrected before the passing run and are not counted as passing evidence.

## 14. Remaining limitations

- No physical-piano or Android microphone evidence.
- No independently verified onset labels or 3–5-take acoustic cells.
- No device/source/placement/piano/room diversity.
- No actual AGC/noise-suppression comparison.
- No acoustic confidence intervals or minimum-effect decision threshold yet.
- No Android input latency, sustained CPU/RSS, APK delta between variants,
  thermal soak, or battery drain result.
- The sparse positive spectral-flux re-arm is debug-only and has passed synthetic
  mechanics, not acoustic false-onset stress.
- C/D/F remain unoptimized research candidates; Basic Pitch remains excluded from
  integration because its Phase 7.5A streaming safety/latency gates failed.

## 15. Exact next action

Open Settings → Developer tools → Guided Piano Recording + Export, enter the
piano/room description, and complete the seven-case pilot first. Export its ZIP
and attach that single file to the next chat for listening/manual-label review.
Then complete the 69-take full matrix and repeat on at least one additional
Android device. Select a winner only if acoustic very-soft recall materially
exceeds B while wrong-note, octave, residual, silence, and repeated-note false
advances remain near zero with acceptable latency and Android cost. Otherwise
retain B.
