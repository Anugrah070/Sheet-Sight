# Phase 7.5A — score-constrained piano recognition research and prototype benchmark

Date: 2026-08-10  
Status: research/prototype complete; physical-piano benchmark pending; production detector unchanged by this phase

## Decision

There is **no production winner yet**. The deterministic fixtures establish that the benchmark and safety gates work, but they do not establish acoustic-piano accuracy. The strongest spectral prototype, D, improved synthetic soft/overall recall but failed the repeated-restrike gate. Basic Pitch failed the more important safety gates by accepting a lower octave as the expected pitch and by accepting a residual harmonic as the next octave. It also has approximately 1.3 s of availability latency when used with its upstream batch window.

Production integration is therefore **not justified**. The next candidate to validate is an onset-gated, score-constrained log-frequency/harmonic hybrid which reuses the existing PracticeEngine exactly-once/re-arm contract. Thresholds must not be tuned from the synthetic results below.

## Evidence labels

- **Verified fact** means a statement supported by public first-party documentation, a published paper, repository source, or inspected SheetSight source.
- **Inference** means a bounded product/engineering conclusion from verified observations. It is not a claim about a private implementation.
- **Unknown** means the public material or the tests run here do not establish the answer.

## 1–3. What is and is not publicly known about flowkey

### Verified facts

| Topic | Publicly established behavior |
|---|---|
| Acoustic microphone | flowkey states that an acoustic piano can be recognized through a device's built-in microphone. It assumes A4 = 440 Hz, recommends limiting background noise and tuning to standard pitch, and suggests repositioning the device or opening the piano lid/top if notes are missed. [flowkey acoustic-piano help](https://help.flowkey.com/en/articles/651003-can-i-use-an-acoustic-piano-with-flowkey) |
| Wait Mode | flowkey describes Wait Mode as listening to the user's playing and waiting for the right notes. [flowkey product page](https://www.flowkey.com/en) |
| Chords | The public product page advertises interactive practice of notes and chords. This establishes product behavior, not the recognition architecture or exact chord acceptance rule. [flowkey product page](https://www.flowkey.com/en) |
| MIDI | flowkey documents USB MIDI and Bluetooth MIDI on Android. Its wording says a MIDI connection gives “nearly perfect” recognition because digital data is easier to process than an acoustic signal. That is a vendor statement, not an independent accuracy measurement. [flowkey Android MIDI help](https://help.flowkey.com/en/articles/412902-connect-your-instrument-to-your-android-device) |
| Microphone limitations | The Android help page explicitly says the microphone may not reliably detect played notes in some instances and recommends direct MIDI for greater reliability. [flowkey Android MIDI help](https://help.flowkey.com/en/articles/412902-connect-your-instrument-to-your-android-device) |
| Current device guidance | The current compatibility page lists Android 11 or later plus USB-host support; it also lists current iOS and desktop/browser categories. [flowkey compatible devices](https://help.flowkey.com/en/articles/650972-which-devices-are-compatible-with-flowkey) |
| Headphones | flowkey documents using Wait Mode while headphones are connected to a digital piano and describes wired options for combined audio/MIDI setups. [flowkey headphone help](https://help.flowkey.com/en/articles/651019-practicing-with-headphones) |

### Inference

- Wait Mode is visibly score-aware at the product level because it waits for “the right notes.” Public information does **not** reveal whether the acoustic implementation uses templates, CQT, AMT, DTW, a neural network, or another method.
- Direct MIDI should avoid microphone, room, tuning, and harmonic-separation errors. This follows from the input modality, but it does not validate flowkey's “nearly perfect” marketing phrase as a measured percentage.

### Unknown

- The microphone-recognition algorithm and model, if any.
- Soft/pp-note thresholds or published soft-note performance.
- Recognition latency or a latency target.
- The exact multi-note/chord decision rule.
- Register-, phone-, room-, or piano-specific error rates.
- Whether the public acoustic and MIDI paths share any internal recognition logic.

**Flowkey's exact recognition accuracy is not publicly established.** No trustworthy public independent benchmark was found that reports expected-note recall, wrong-note rejection, octave errors, repeated-note behavior, and latency for flowkey on a controlled acoustic-piano dataset. No private code, APK, model, asset, or traffic was inspected.

## 4. Current SheetSight detector baseline

### Inspected Practice 7.x path

```text
Android AudioRecord (mono PCM16, 22,050 Hz, UNPROCESSED when available)
  -> overlapping 4,096-sample frames, 1,024-sample hop
  -> YIN over 27.5–4,186 Hz
  -> StablePitchFilter (confidence, adaptive/register-aware signal gate,
     two-frame stability, release/amplitude-rise/pitch-transition evidence)
  -> PitchMatcher (nearest MIDI, ±35 cents, one expected pitch)
  -> PracticeTimingMatcher
  -> PracticeEngine (only owner of score progression)
```

Verified invariants in the existing code:

- Only a stable matching pitch event can advance a playable step.
- A wrong pitch, low confidence, silence, or release cannot advance a playable step.
- The engine consumes one event at most once. A sustained C4 cannot consume `C4,C4,C4`; a later identical step requires release/re-arm or qualifying amplitude-rise evidence.
- Rests/ties are clock-driven; they do not grant recognition authority to the audio layer.
- Articulation/release tracking is diagnostic and does not move the Practice pointer.
- Chords are currently unsupported by `PitchMatcher`.

Current weaknesses relevant to this phase:

- YIN searches the complete piano frequency range before the expected score pitch is considered.
- A single estimated fundamental is the acoustic hypothesis; expected-pitch harmonic patterns and explicit competing-note scores are absent.
- The fundamental/harmonic relationship is not used to distinguish C3/C4/C5 or a new octave onset from a lower note's residual harmonic.
- Stability is primarily two consecutive pitch identities, not a bounded multi-feature evidence state.
- The current worktree noise floor is adaptive but is an EMA-like estimator, not the rolling robust estimator prototyped here.
- The higher layers receive `PitchFrame`, not the shared spectrum/activation vector needed by score-constrained spectral or chord-capable candidates.
- `UNPROCESSED` input is requested but may fall back to `DEFAULT`; actual device AGC/noise suppression and its soft-note effect were not measured here.

Baseline naming in this report is deliberate:

- **A** reconstructs the committed pre-Phase-7.5 fixed-RMS YIN/stability behavior from repository `HEAD` in debug-only code.
- **B** exercises the current working-tree production YIN plus the existing uncommitted adaptive/register-aware Phase 7.5 improvements. Those production changes pre-existed this research work and were not authored or replaced here.

The README records an earlier OnePlus physical-piano matrix with edge-register/normal-distance and legato problems. No new physical-piano result is inferred from that historical observation.

## 5–7. Candidates and benchmark infrastructure

### Problem framing

The benchmark asks, “Does the audio support the current expected step?” Candidate sets are deliberately local: expected pitch, nearby semitones, octaves, recent previous pitch, and a few next pitches. Score context narrows hypotheses; it never creates acoustic evidence and never moves the pointer independently.

### Implemented isolated candidates

| ID | Candidate | Prototype behavior |
|---|---|---|
| A | committed fixed-RMS YIN baseline | Full-range YIN plus reconstructed fixed signal threshold/stability behavior. |
| B | current adaptive YIN | Current worktree YIN and `StablePitchFilter`; no production code was changed for the benchmark. |
| C | score-constrained harmonics | Windowed Goertzel probes at expected/candidate fundamentals and harmonics; tolerates a weak fundamental; scores competitors; bounded temporal fusion. |
| D | score-constrained log frequency | Variable-window, 24-bin/octave-equivalent log-frequency probes with harmonic aggregation and octave penalty. This is a CQT-style prototype, not a third-party CQT library. |
| E | Basic Pitch ONNX | Official Basic Pitch ONNX serialization, default 0.5 onset threshold, then the same explicit expected-step pointer. No Android or production integration. |
| F | hybrid | YIN evidence fused with C's score-constrained harmonic evidence and the same temporal decision state. |

C/D/F implement `NoEvidence`, `Ambiguous`, `AcceptedExpectedNote`, and `WrongNote`. Their adaptive floor uses a rolling median of recent safe/low-evidence frames, bounded upward so a transient or piano tail cannot rapidly become the learned room floor. Low-SNR expected evidence requires more frames than normal evidence. Acceptance also requires a margin over competing notes and onset/re-arm evidence.

### Dataset/harness

- A debug-source-set interface keeps raw PCM and prototypes outside production.
- A local WAV loader accepts explicit mono PCM16 22,050 Hz fixtures; resampling is never hidden.
- The tracked TSV is only a capture template. Real recordings belong under ignored `tools/practice-audio/local-recordings/` and require `manual_verified=true` plus reviewer identity before being counted.
- The app still stores no ordinary user microphone PCM. A developer must explicitly create each controlled fixture.
- Twenty deterministic, author-defined synthetic fixtures cover isolated expected/wrong notes, neighboring semitone, both octave directions, weak fundamentals, very soft/soft/normal/strong attacks, repeated restrikes, one sustained repeat, legato, residual sustain, a new octave over sustain, note after silence, noise, and silence.
- All candidates use those exact same PCM WAVs and labels. Synthetic signals validate mechanics and regressions only; they are not recordings of a piano, Android microphone, room, or AGC.
- Multiple real takes per cell are still required. The template's `-01` rows are placeholders, not a statistically useful acoustic corpus.

## 6. License and deployment screening

| Candidate | Repository / research | Code license | Checkpoint/model license finding | Commercial/attribution implication | Mobile decision |
|---|---|---|---|---|---|
| SheetSight C/D/F | Independently implemented here; harmonic/log-frequency methods are standard DSP | Project license | No model | No new third-party artifact | Keep as research candidates |
| Spotify Basic Pitch | [repository](https://github.com/spotify/basic-pitch), [paper](https://arxiv.org/abs/2203.09893) | Apache-2.0 option documented by upstream | ONNX/TFLite/CoreML/TF serializations are committed with the project; no separate restrictive model notice was found | Commercial use is compatible with the Apache option; retain license/copyright/NOTICE obligations and obtain release legal review | Feasible to execute offline in principle, but the upstream path is batch-oriented and unsafe on this benchmark |
| Magenta Onsets and Frames | [paper](https://arxiv.org/abs/1710.11153), [archived implementation](https://github.com/magenta/magenta/tree/main/magenta/models/onsets_frames_transcription) | Apache-2.0 repository | A pretrained MAESTRO checkpoint is linked, but no separate checkpoint license was established in this review | Do not ship weights until their license provenance is explicitly confirmed | TF1-era CNN/LSTM repository is archived/inactive; no supported Android artifact; reject for this phase |
| ByteDance piano transcription | [repository](https://github.com/bytedance/piano_transcription), [paper](https://arxiv.org/abs/2010.01815) | Apache-2.0 repository | Checkpoints are published, but no separately stated checkpoint license was established here | Code is permissive; weights need explicit provenance confirmation before distribution | Archived, PyTorch/CUDA-oriented CRNN, no upstream ONNX/TFLite Android package found; reject for this phase |
| MT3 | [repository](https://github.com/magenta/mt3) | Apache-2.0 repository | Pretrained checkpoints are linked; a separate checkpoint license was not established here | Do not distribute a checkpoint without explicit confirmation | T5X multi-instrument transformer with Colab/GPU workflow and no mobile artifact; reject for Practice latency/size |
| Matchmaker score following | [repository](https://github.com/pymatchmaker/matchmaker), [ISMIR 2025 paper](https://arxiv.org/abs/2510.10087) | Apache-2.0 | No required learned checkpoint for its CQT/chroma/online-alignment reference paths | Preserve Apache notices if code is reused; algorithms can also be independently implemented from papers | Useful research reference; Python library is not an Android drop-in and a follower must never advance Practice autonomously |

Published AMT benchmark scores are not SheetSight correctness figures. For example, ByteDance reports a 96.72% onset F1 on MAESTRO, and Onsets-and-Frames reports its own transcription metrics; neither evaluates the score-constrained, wrong-note-must-not-advance policy on Android microphone audio.

## 8–13. Deterministic comparative results

These figures are developer metrics from 20 synthetic clips (48.2 s total), not acoustic accuracy. Percentages are shown only for engineering evaluation.

| Candidate | Expected recall | Wrong-note rejection | False-advance rate | Soft recall | Octave confusion | Repeat-case error | Median / P95 acceptance latency | Desktop RTF |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A fixed-RMS YIN | 72.2% (13/18) | 100% (5/5) | 0% | 37.5% | 0% | 0% | 57 / 103 ms | 0.0179–0.0263 |
| B current adaptive YIN | 88.9% (16/18) | 100% (5/5) | 0% | 75.0% | 0% | 0% | 57 / 150 ms | 0.0210–0.0234 |
| C harmonics | 88.9% (16/18) | 100% (5/5) | 0% | 100% | 0% | 50% | 126 / 139 ms | 0.3559–0.4307 |
| D log frequency | 94.4% (17/18) | 100% (5/5) | 0% | 100% | 0% | 50% | 103 / 150 ms | 0.0525–0.0577 |
| E Basic Pitch ONNX | 94.4% (17/18) | 80% (4/5) | 30% (3/10 negative steps) | 100% | 50% | 0% | 1,314 / 1,314 ms availability | 0.0369 |
| F hybrid | 88.9% (16/18) | 100% (5/5) | 0% | 100% | 0% | 50% | 80 / 148 ms | 0.3855–0.4649 |

The non-neural RTF ranges above are the observed range across passing Windows/JVM runs and include unoptimized debug reference code plus fixture copies. Recognition outcomes and acceptance latencies were stable across those runs. Basic Pitch timing is one Windows/Python/ONNX Runtime CPU run. These are not Android CPU or audio-I/O timings.

### Register recall

| Candidate | Low | Mid | High |
|---|---:|---:|---:|
| A | 50.0% | 78.6% | 50.0% |
| B | 100% | 85.7% | 100% |
| C | 100% | 85.7% | 100% |
| D | 100% | 92.9% | 100% |
| E | 100% | 92.9% | 100% |
| F | 100% | 85.7% | 100% |

### Dynamic recall

| Candidate | Very soft | Soft | Normal | Strong |
|---|---:|---:|---:|---:|
| A | 0% | 60.0% | 100% | 100% |
| B | 100% | 60.0% | 100% | 100% |
| C | 100% | 100% | 77.8% | 100% |
| D | 100% | 100% | 88.9% | 100% |
| E | 100% | 100% | 88.9% | 100% |
| F | 100% | 100% | 77.8% | 100% |

### Failure interpretation

- **Very soft:** A missed all three very-soft fixtures. B accepted all very-soft fixtures but missed two events in the soft legato sequence, producing 75% combined soft recall. C/D/F accepted all soft fixtures, but that synthetic improvement cannot justify lowering or changing production thresholds.
- **Wrong notes/semitones:** A/B/C/D/F rejected D4, G4, C-sharp4, and both octave-error fixtures when C4 was expected. E rejected four of five but falsely accepted C4 from the harmonic structure of a played C3.
- **Octaves/residual:** E's lower-octave error produced the 50% octave-confusion rate. It also prematurely accepted expected C5 from a sustained C4 harmonic in both residual fixtures; one was a false advance with no C5, and one consumed C5 before the real C5 onset. C/D/F's multi-harmonic comparison and onset state rejected these synthetic tails.
- **Repeated notes:** A/B passed both repeat cases. C/D/F correctly prevented one sustained C4 from consuming three steps, but failed to accept all three actual restrikes; one of two repeat scenarios therefore failed. This alone blocks production replacement.
- **Silence/noise:** No candidate advanced the expected pointer in the silence or room-noise fixtures. Basic Pitch emitted many raw non-expected onset activations in its silence output, reinforcing that raw AMT output still needs score/onset/noise gates even though none happened to equal the expected C4 in that clip.
- **Latency:** A/B are immediate on these fixtures. The spectral prototypes need 80–150 ms median/P95 evidence windows, which could be acceptable only after Android measurement. E's model inference was fast relative to audio length, but upstream 43,844-sample windows make the relevant first-window availability approximately 1.3 s after a 500 ms onset; fast batch inference is not low streaming latency.

## 14. CPU, memory, model, APK, and battery observations

| Candidate | Cold init | Processing per 1,024-sample-equivalent block | Model bytes / APK implication | Memory and battery status |
|---|---:|---:|---|---|
| A | 0.52–0.97 ms | 0.84–1.23 ms | No model; debug harness only | Detector workspace is small primitive arrays; Android RSS/CPU/battery not measured |
| B | 0.24–0.90 ms | 0.98–1.10 ms | No new model | Android RSS/CPU/battery not measured in this phase |
| C | 0.023–0.026 ms | 16.69–20.19 ms | No model; debug-only Kotlin | Reference implementation is CPU-heavy but below real time on desktop; must be optimized/profiled on device |
| D | 0.024–0.030 ms | 2.46–2.71 ms | No model; debug-only Kotlin | Best non-neural prototype cost here; Android RSS/CPU/battery still unknown |
| F | 0.027–0.059 ms | 18.08–21.80 ms | No model; debug-only Kotlin | Too costly to judge without optimization; Android status unknown |
| E Basic Pitch | 91.0 ms ORT session init | 1,776 ms total for 48.2 s audio (RTF 0.0369) | ONNX = 230,444 bytes, SHA-256 `2C3C1D144BFA61AD236E92E169C13535C880469A12A047D4E73451F2C059A0EC`; upstream TFLite serialization is about 204 KB. Actual compressed APK delta was not built. | Whole Python process working set after init was 56,995,840 bytes; this is **not** model-only or Android memory. SheetSight already depends on ONNX Runtime Android, but model-operator support, sustained Android CPU/RAM, thermals, and battery remain unmeasured. |

Basic Pitch's public implementation downmixes to mono, resamples to 22,050 Hz, provides polyphonic note/onset/contour outputs, and ships TFLite/ONNX/CoreML/TensorFlow serializations. The evaluated ONNX uses a 43,844-sample input (roughly two seconds). Upstream still has an open real-time-streaming feature request, so an Android streaming design would be new engineering rather than a documented supported path. [Basic Pitch README](https://github.com/spotify/basic-pitch/blob/main/README.md), [streaming issue](https://github.com/spotify/basic-pitch/issues/171)

## 15–16. Recommended architecture and integration gate

Recommended next **experiment**, not a selected production winner:

```text
microphone PCM
  -> verify device DSP / optional safe conditioning by measurement
  -> robust rolling noise distribution updated only in safe non-note periods
  -> onset / spectral-flux / amplitude-rise evidence
  -> one shared log-frequency representation
  -> narrow score candidate set:
       expected + previous + next few + neighboring semitones + octaves
  -> per-candidate fundamental-independent harmonic pattern
  -> expected-vs-competitor margin + onset + temporal stability
  -> NoEvidence | Emerging/Ambiguous | StableWrong | AcceptedExpectedNote
  -> existing PracticeEngine exactly-once/re-arm gate
```

Acceptance must require actual expected-pitch evidence, a sufficient margin over the strongest competitor, bounded temporal support, and new-onset/re-arm evidence. Strong competing D4 with weak leaked C4 must be `WrongNote(D4)`, not correct C4. A score follower may provide a local-position prior, but only `PracticeEngine` may advance and it may advance by exactly one explicit correct step.

For future chords, the shared representation should expose one activation/confidence per locally relevant pitch so the question can become “are `{C4,E4,G4}` all present?” Monophonic reliability remains the release gate; chroma alone is insufficient because it discards octave identity.

**Production integration: not justified.** No feature flag or production interface was added because no candidate passed both the improvement and repeated-note/safety gates on real audio.

## 17. Files created or modified by Phase 7.5A work

Created:

- `app/src/debug/java/com/sheetsight/app/data/audio/benchmark/PracticeRecognitionBenchmark.kt`
- `app/src/debug/java/com/sheetsight/app/data/audio/benchmark/BenchmarkDetectors.kt`
- `app/src/test/java/com/sheetsight/app/data/audio/benchmark/SyntheticPracticeBenchmarkFixtures.kt`
- `app/src/test/java/com/sheetsight/app/data/audio/benchmark/PracticeRecognitionBenchmarkTest.kt`
- `tools/practice-audio/README.md`
- `tools/practice-audio/dataset-template.tsv`
- `tools/practice-audio/basic_pitch_benchmark.py`
- `docs/phase-7.5a-recognition-research.md`

Modified:

- `.gitignore` — ignores local benchmark recordings.

Generated, ignored build artifacts include the synthetic WAV export, Basic Pitch ONNX file, Python dependency target, and JSON report under `app/build/`. Production audio files and Practice recognition classes were not changed by this research phase. Other pre-existing worktree modifications belong to the ongoing Phase 7.5 work and are not claimed here.

## 18–19. Tests actually run and physical-piano tests

Actually run:

- `PracticeRecognitionBenchmarkTest`: 8 tests, 0 failures. This covers dataset dimensions/provenance, same-fixture candidate execution, octave/semitone rejection, repeat-sustain safety, silence/noise, WAV round-trip, manifest verification enforcement, and exact WAV export.
- Focused existing regressions: 51 tests, 0 failures across `YinPitchDetectorTest`, `StablePitchFilterTest`, `AcousticNoteEventTrackerTest`, `AcousticValidationSessionTest`, `PitchMatcherTest`, and `PracticeEngineTest`.
- Complete `testDebugUnitTest` suite: 334 tests in 61 suites, 0 failures, 0 errors, 0 skipped.
- Basic Pitch ONNX replay over the same 20 exported PCM fixtures; the summarized JSON metrics are reported above.

Initial attempts included one SDK-sandbox failure, one timed-out Gradle run, and one prototype test-configuration failure which was corrected before the passing runs. Those attempts are not counted as passing evidence.

Physical-piano/device tests actually run in this Phase 7.5A execution: **none**. No piano or Android microphone was accessible. The README's earlier OnePlus observations are historical context, not a new validation result. No claim of flowkey-equivalent or superior accuracy is made.

## 20. Remaining limitations

- No manually verified acoustic WAV takes; no piano/room/phone diversity and no statistical confidence intervals.
- No device AGC/noise-suppression characterization, phone-placement comparison, or 44.1/48 kHz capture/resampling study.
- No Android cold start, sustained CPU, RSS, thermals, battery, audio-I/O latency, or APK-size measurement for a candidate.
- The synthetic envelopes/harmonics do not reproduce soundboard coupling, inharmonicity, pedal resonance, duplex scaling, room reflections, microphone clipping, OEM DSP, or pianist variability.
- C/D/F are reference implementations, not optimized DSP. D is CQT-style variable-window probing, not a full reusable CQT implementation.
- Neural research metrics use different datasets/objectives and cannot rank Practice-pointer safety.
- Checkpoint licensing for O&F, ByteDance, and MT3 was not explicit enough for distribution; these weights remain rejected pending provenance review.
- Chord acceptance is prepared conceptually but not implemented or benchmarked.
- The real-capture template needs several manually reviewed takes per register/dynamic/error/placement cell, not one.

## 21. Exact next phase

1. Explicitly record local, consented, mono PCM fixtures from a physical acoustic piano on at least two Android devices and at least three placements. Capture 3–5 manually verified takes for every template cell, including pp-like edge registers, neighbor semitones, C3/C4/C5 confusion, repeated restrikes, legato, sustain residuals, and noise-only periods. Retain no ordinary user audio.
2. Record the actual Android audio source and DSP path for every take (`UNPROCESSED` availability/fallback, sample rate, AGC/noise-suppression behavior) and label onsets/pitches manually.
3. Run A/B/C/D/F on the exact same acoustic fixtures. Keep E only as a documented neural reference unless a genuinely streaming window strategy first passes the no-false-advance gates.
4. Repair C/D/F repeated-note re-arm using independent onset/spectral-flux evidence while preserving the sustained-note one-advance test. Do not globally lower RMS thresholds.
5. Measure Android median/P95 onset-to-decision latency, per-hop processing, sustained CPU, RSS, thermal/battery behavior, and APK delta for the best safe candidate.
6. Select a winner only if it materially improves B's acoustic very-soft recall while maintaining near-zero wrong, silence, octave, residual, and repeat false advances. Otherwise retain B.
7. If and only if a clear physical/device winner exists, put it behind the existing recognition interface and a developer feature flag, retain B as fallback, rerun the full real-piano matrix, and leave `PracticeEngine`, score UI, timing, articulation, and release ownership unchanged.
