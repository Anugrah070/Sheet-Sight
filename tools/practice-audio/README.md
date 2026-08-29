# Practice audio benchmark data

This directory defines the local-only Phase 7.5A/7.5B acoustic benchmark contract.
It is not used by production Practice Mode.

## Privacy and retention

- Never record implicitly. A developer must explicitly create every fixture.
- Put real-piano WAV files under `local-recordings/`; that directory is ignored
  by Git.
- Use mono, little-endian PCM16 WAV at 22,050 Hz. Resampling is intentionally
  not hidden by the harness: resample explicitly and retain the original local
  recording when microphone/DSP provenance matters.
- Do not copy ordinary user practice audio into the benchmark.
- Delete local recordings when they are no longer needed. The app itself still
  persists no microphone PCM.

The instrumented capture below is the only Android path in this project that
persists microphone PCM. It refuses to run without an explicit confirmation
argument, writes only to the app's external `phase75b/captures` directory, and
is never called by ordinary Practice Mode.

## Ground truth

Copy `dataset-template.tsv` to an untracked manifest beside the local WAV files.
Every row must be reviewed by a human and set `manual_verified=true` before it
can contribute to an acoustic-accuracy report. `expected_onsets_ms` contains one
entry per score step; use `-` when the expected step was not actually played.
`performed_midi` records what the developer intentionally played, including a
wrong note. MIDI numbers are used only as pitch identities; capture is audio.

Run all candidate detectors against exactly the same reviewed rows. Keep raw
recordings local; export only aggregate metrics and bounded derived diagnostics.
`BenchmarkManifestReader` rejects unverified rows (or a verified row without a
reviewer), and `LocalBenchmarkDatasetLoader` resolves the reviewed WAV paths
under this directory before replay. A path that escapes the benchmark directory
is rejected.

Verified rows must also include device model, Android version, requested and
actual sample rate, requested and actual audio source, UNPROCESSED support,
observed AGC/noise-suppressor state, piano, placement, room condition, and UTC
capture time, routed input device, and AudioRecord buffer size. The Android capture test writes
those facts beside each WAV. Copy
them into the manifest only after listening to the complete recording and
manually checking the performed pitches and onset labels.

The JVM benchmark also has deterministic, human-authored synthetic fixtures.
They exercise the harness and safety invariants, but they are not acoustic-piano
evidence and must never be presented as real recognition accuracy.

## In-app guided recorder

For normal collection, open **Settings → Developer tools → Guided Piano Recording
+ Export (Debug)**. Choose the short seven-case pilot first or the full 69-take
matrix, enter the piano and room description, and start the session.

The live bar responds to microphone level and shows the latest detected piano
pitch/confidence. For each prompt:

1. Put the phone in the placement shown on screen.
2. Press **Record take**.
3. Wait for the two-second visual countdown.
4. Perform only the displayed instruction while the recording bar fills.
5. Redo the last take or continue to the next prompt.

Use **Export ZIP** at any time to save one portable file containing every
captured WAV, capture provenance, and `manifest.tsv`. All rows intentionally
remain `manual_verified=false`; attaching the ZIP to a later chat does not turn
them into ground truth until a human listens and verifies the labels/onsets.
Preview listening is memory-only, and only prompted Record-take intervals enter
the ZIP.

## Build a 3–5 take capture plan

The tracked template contains one unverified base row per important case. Create
an ignored working manifest with three through five takes per row:

```powershell
powershell -ExecutionPolicy Bypass -File tools/practice-audio/New-Phase75bManifest.ps1 -TakesPerCase 3
```

The default plan currently creates 69 rows and rotates its first three takes
through normal, near-keys, and open-lid placements. Add more piano, room, device,
and placement groups rather than treating repeated recordings from one setup as
device diversity. Do not set `manual_verified=true` in bulk.

## Explicit Android microphone capture

Connect the device, then install the debug APK and test APK:

```powershell
./gradlew installDebug installDebugAndroidTest
```

Capture one deliberately created fixture. The three-second `lead_in_ms` occurs
before recording starts; once the command begins recording, play the labeled
case immediately. Increase it if the pianist needs more setup time.

```powershell
adb shell am instrument -w `
  -e class com.sheetsight.app.data.audio.benchmark.AndroidBenchmarkCaptureInstrumentedTest `
  -e confirm_developer_capture I_UNDERSTAND_PCM_IS_PERSISTED `
  -e fixture_id repeated-restrikes-01 `
  -e piano "upright-serial-or-stable-id" `
  -e phone_placement NORMAL `
  -e room_condition QUIET `
  -e audio_source UNPROCESSED `
  -e sample_rate_hz 22050 `
  -e lead_in_ms 3000 `
  -e duration_ms 5000 `
  com.sheetsight.app.test/androidx.test.runner.AndroidJUnitRunner
```

Run a second capture with `audio_source DEFAULT` where the matrix calls for a
source comparison. The test records whether UNPROCESSED was advertised, the
source actually configured, actual sample rate, AGC/noise-suppressor availability
and enabled state, routed input device, and AudioRecord buffer size. These are
observations, not proof that an OEM applies no other DSP.

Pull the explicitly captured pair, then remove the device copy after confirming
the pull succeeded:

```powershell
adb pull /sdcard/Android/data/com.sheetsight.app/files/phase75b/captures tools/practice-audio/local-recordings/device-id
```

Listen to every WAV. Fill the expected MIDI steps, performed MIDI, and manually
measured onset times. Set `manual_verified=true` and add the reviewer only after
that review. If the actual WAV rate is not 22,050 Hz, retain the original and
create a plainly named PCM16 mono 22,050 Hz derivative; resampling must never be
implicit. The manifest provenance continues to describe the original Android
capture path.

## Android replay and cost report

Stage a completed manifest plus its referenced WAV paths under the app-specific
replay directory, preserving relative paths:

```powershell
adb shell mkdir -p /sdcard/Android/data/com.sheetsight.app/files/phase75b/replay/local-recordings
adb push tools/practice-audio/local-recordings/manifest.tsv /sdcard/Android/data/com.sheetsight.app/files/phase75b/replay/manifest.tsv
adb push tools/practice-audio/local-recordings/device-id /sdcard/Android/data/com.sheetsight.app/files/phase75b/replay/local-recordings/device-id
adb shell am instrument -w `
  -e class com.sheetsight.app.data.audio.benchmark.AndroidAcousticReplayInstrumentedTest `
  -e manifest manifest.tsv `
  com.sheetsight.app.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/com.sheetsight.app/files/phase75b/replay/android-benchmark-report.json tools/practice-audio/local-recordings/device-id/
```

Replay rejects an unverified row, a missing reviewer, incomplete provenance, an
escaping path, or a WAV that is not mono PCM16 at 22,050 Hz. It runs A/B/C/D/F
over the exact same clips and reports recall, safety rates, latency, register and
dynamic slices, cold init, processing time, real-time factor, process PSS/CPU,
APK bytes, battery snapshots, and thermal status. Capture buffer size is not a
measured end-to-end audio latency; measure that separately with a physical
loopback or timestamped acoustic protocol before selecting a winner.
