# SheetSight

An offline, Android-native OMR (Optical Music Recognition) app: import a
PDF or image of printed sheet music, recognize it into structured
MusicXML on-device, then (eventually) correct it, analyze it, and use it
for real-time piano practice against a microphone/MIDI input.

This README describes **only what exists in the current codebase**. Where
something is scaffolded but not implemented, or planned but not started,
it's labeled as such explicitly.

---

## 1. Project overview

SheetSight's intended end-to-end flow:

1. **Import** a PDF/JPG/PNG of sheet music.
2. **Recognize** it via an on-device OMR pipeline into MusicXML (no cloud
   calls, no external OMR service).
3. **Edit** the recognized notation to correct OMR mistakes.
4. **Analyze** the score (key, chords, cadences, intervals, motifs, ...).
5. **Practice** by playing the piece on a real piano; the app listens via
   microphone or MIDI and advances a cursor through the score note-by-note.

The app is offline-first end to end: local Room storage, on-device OMR via
ONNX Runtime Mobile, no network dependency for any core feature.

---

## 2. Current implementation status

### Completed and implemented
- **App shell**: single-Activity Compose app (`MainActivity`), Material 3
  theming, bottom-tab navigation (`SheetSightNavHost`) across five tabs —
  Library, Editor, Practice, Analysis, Settings — plus a Preview screen.
- **Library tab**: fully functional — grid/list view, search, sort, file
  import via the system file picker, favorite/delete, backed by Room.
- **Score persistence**: Room database (`AppDatabase`, `ScoreDao`,
  `ScoreEntity`) and a `ScoreRepository`/`ScoreRepositoryImpl` pair
  exposing `Score` domain models via `Flow`.
- **Import pipeline**: `ImportScoreUseCase` validates a picked PDF/JPG/PNG,
  copies it into app-private storage (`ScoreFileStorage`, under
  `filesDir/scores/`), determines page count for PDFs, and persists a new
  `Score` row. **It does not run OMR** — `Score.musicXmlPath` is left
  `null` by this use case.
- **Preview screen**: renders PDF pages via `android.graphics.pdf.PdfRenderer`
  with pinch-zoom/pan and can run the current page through the complete
  recognition route, persist the generated MusicXML path, and open it in
  the notation viewer.
- **Dependency injection**: Hilt modules for the database, repositories,
  qualified coroutine dispatchers, and the OMR module.
- **OMR pipeline integration**: `DefaultScoreOmrProcessor` runs imported
  image/PDF pages through the complete recognition and MusicXML-export route.
  `OnnxOmrEngine` separately exposes the older decode-through-staff-grid seam.
- **OMR pipeline components (part of a 311-test passing JVM suite)**:
  - oemer-compatible image preprocessing and tiling.
  - ONNX Runtime tensor preparation and real model inference.
  - prediction-map merging and class-mask extraction.
  - dewarping geometry estimation, gap-bridging, coordinate mapping, and
    remap application.
  - **Staffline extraction**: vertical row-density histogram, z-score
    normalization, peak detection (verified against scipy), and 5-line
    staff grouping.
  - **Staff-grid assembly and validation**: integrated pipeline for
    extracting stafflines across zones, detecting barlines, inferring
    track counts via voting, assigning segments, and validating
    consistency.
  - **Noteheads, note grouping, and rhythm evidence**: extracted into
    immutable candidates with explicit unresolved rhythm states.
  - **Phase 3 symbol extraction**: geometric barlines plus Android ONNX
    Runtime exports of oemer 0.1.8's clef, sharp/flat/natural, coarse-rest,
    and above-eighth-rest SVMs. Real classified rest candidates are
    produced and consumed directly by rhythm.
  - **Phase 4 classifier verification**: all 384 deterministic
    class-balanced support/stress vectors produce exact labels on desktop
    and on a OnePlus CPH2707 (Android 16, ARM64). Android decision scores
    stay within one ULP of desktop ONNX Runtime and within `8e-6` of
    sklearn 1.2.0.
  - **Phase 5 classified-rest rhythm integration**: typed classifier
    results now produce deterministic rest rhythm results for quarter
    through sixty-fourth values, including augmentation dots. The trained
    model's combined whole-or-half class remains explicitly unresolved.
  - **Semantic score construction**: validated staff groups, barlines,
    classified symbols, note groups, and rhythm results are converted into
    an immutable, image-independent `SemanticScore`. Evidence-backed
    measures, semantic events, clef-aware pitches, accidental state, source
    provenance, and structured validation warnings are implemented.
  - **MusicXML output route**: `DefaultScoreOmrProcessor` adapts image/PDF
    pages to the complete smoke-runner pipeline through semantic construction
    and MusicXML export, then persists the generated file for the app UI.
- **Editor notation viewer**: generated MusicXML is hardened-parsed, laid out,
  and rendered as real notation with paging/system geometry and zoom controls.
  Interactive notation editing is not implemented yet.
- **Practice Mode (Phases 7.0-7.4)**: imports uncompressed MusicXML, builds a
  deterministic `PracticeSequence`, captures one local microphone stream,
  performs monophonic piano YIN pitch detection, stable-note/onset filtering,
  correct/wrong matching, repeated-note re-arm protection, notation
  highlighting and score following, BPM/count-in/practice-clock timing,
  Early/OnTime/Late feedback, timed rests, and pause/resume.
- **Informational articulation awareness (Phases 7.3-7.4)**: accepted notes create
  bounded session-local acoustic events which reuse existing pitch confidence
  and RMS data, tolerate detector dropouts, debounce release, exclude paused
  time, and report articulation-aware duration outcomes without changing
  pointer advancement. Phase 7.4 adds a six-sample, device/environment release
  calibration with robust derived statistics and a versioned compact preference
  profile; raw PCM is never persisted. MusicXML ties, slurs, staccato, tenuto,
  accent/strong-accent, staccatissimo, and fermata are preserved explicitly.
  Tie chains retain their source notes for rendering while using combined
  sounding duration and no-onset continuation semantics in Practice Mode.

### In progress
- **Production integration**: semantic construction is available as a
  tested component and developer smoke stage, but `OnnxOmrEngine` still
  stops at its documented later-phase integration seam.
- **Testing**: 311 JVM tests pass. The Phase 4 classifier parity
  instrumented test passes on the connected OnePlus device.
- **Practice acoustic tuning**: deterministic calibration/profile and tracker
  behavior are JVM-tested; Phase 7.4 has not yet been calibrated on a physical piano and
  sustain pedal in the current implementation session.

### Planned / not yet implemented
- Interactive notation editing remains planned; the current Editor is a
  read-only MusicXML notation viewer.
- Analysis tab (key/chord/cadence/interval/motif detection, overlays) —
  placeholder screen only.
- Practice scoring/grades, persistent analytics/history, dynamics, pedal
  detection/scoring, polyphonic transcription, accompaniment, and MIDI are
  intentionally not implemented.
- Fingering suggestion and multi-page
  side-by-side view, measure bookmarks — not started.

---

## 3. Current OMR pipeline status

The app-facing `DefaultScoreOmrProcessor` route is implemented from image/PDF
input through MusicXML export. Its stages remain independently testable Kotlin
components under `app/src/main/java/com/sheetsight/app/data/omr/`; the older
`OnnxOmrEngine` contract is still only integrated through staff-grid assembly.

### 3.1 OMR foundation / architecture
- `OmrEngine` (interface), `OmrResult`, `OmrState`, `OmrRepository`:
  define the contract for running OMR.
- `OnnxOmrEngine`: coordinates decode through validated staff-grid assembly.
  Its `recognize()` contract still throws `NotImplementedError` at that older
  integration seam rather than fabricating an `OmrResult`.
- `DefaultScoreOmrProcessor`: the UI-facing complete route through semantic
  construction and MusicXML export, including temporary PDF-page rendering.
- `OmrPageDewarpRunner`: orchestrates the end-to-end dewarping flow
  (inference → mask extraction → image alignment → dewarp).
- `di/OmrModule.kt` provides `OrtEnvironment` and binds `OmrEngine`.
- `SheetSightApplication.onCreate()` loads OpenCV natively.

### 3.2 Oemer-compatible preprocessing (`data/omr/preprocessing/`)
- `OmrModelSpec`: verified (via direct ONNX graph introspection, not
  assumption) input/output tensor specs for both models — see section 4.
- `CanonicalImageResizer`: reproduces oemer's `resize_image()` — resizes
  toward ~3.675 megapixels, bicubic, aspect-ratio preserved.
- `ImagePreprocessing`: Bitmap → OpenCV `Mat`, deliberately converting to
  BGR (not RGB) to replicate oemer's own BGR-mislabeled-as-RGB
  training-data quirk.

### 3.3 Image tiling
- `SlidingWindowTiler`: 128px stride, edge-clamped (not padded) windows,
  duplicate-origin-at-edges behavior preserved to match oemer exactly.
  Pure coordinate math (`computeOrigins`) is split out from `Mat` cropping
  for JVM-only unit testing.
- `ImageTile`: one tile — crop, origin coordinates, and `Mat` lifecycle
  (`release()`).
- `OmrPreprocessor`: orchestrates decode → resize → tile per model.

### 3.4 ONNX Runtime inference (`data/omr/inference/`)
- `OrtSessionProvider`: lazily creates and caches one `OrtSession` per
  model, loading model bytes from `assets/`.
- `OmrTensorFactory`: packs tile batches into `OnnxTensor` (UINT8 NHWC, no
  float normalization, matching oemer's raw uint8 input).
- `TileInferenceRunner`: runs `session.run()` per model over a tile batch
  and unpacks results into `TilePrediction`s — real ONNX Runtime
  inference, not a stub.
- `OmrPageInferenceRunner`: orchestrates preprocessing → inference →
  merge for a single page, returning one `OmrPredictionMap` per model.

### 3.5 Prediction-map merging and class-map extraction
- `PredictionMapMerger` / `OmrPredictionMap`: overlap-averages tile
  predictions into one full-page raw prediction map per model
  (`out[y:y+win,x:x+win] += pred; mask += 1; out /= mask`, matching oemer).
- `ClassMaskExtractor` / `OmrClassMasks`: argmaxes each model's raw
  prediction map into the five boolean masks oemer's downstream stages
  need: `staff`, `symbols`, `stemsRests`, `noteheads`, `clefsKeys`.

### 3.6 Dewarping status (`data/omr/dewarp/`) — integrated and verified
- `StaffMaskMorphology`: vertical dilate + horizontal open on staff mask.
- `StafflineGridDetector` / `StafflineGridGrouper` (+ `ConnectedComponents`,
  4-connected): detect and group staffline segments.
- `StafflineGridBridger`: bridges gaps in stafflines via linear regression.
- `DewarpMappingBuilder`: extracts control points from the bridged map.
- `DewarpCoordinateInterpolator`: dense coordinate map construction
  (row-then-column linear interpolation approximation).
- `ImageMaskAligner`: ensures pixel alignment between the canonical image
  and masks.
- `DewarpRemapper`: cubic remap application for image and all 5 masks.
- `DewarpPipeline`: orchestrates the full geometric transformation.

### 3.7 Staffline extraction (`data/omr/staffline/`) — implemented and verified
- `PeakFinder`: pure-Kotlin port of `scipy.signal.find_peaks`, verified
  bit-for-bit against scipy 1.17.1 for height/distance/prominence.
- `ZoneStafflineExtractor`: orchestrates per-zone extraction: z-scored row
  density → peak detection → 5-line grouping → pixel assignment. Labeling
  follows oemer's top-to-bottom convention (FIRST=topmost line).
- `Staffline`: data model for a single line with lazy geometry (y-center,
  slope via OLS).
- `ZoneStaff`: a group of exactly five `Staffline`s within a vertical zone.

### 3.8 Staff-grid assembly and tracking (`data/omr/track/`) — integrated

The components below orchestrate the transition from dewarped masks to a
structured, validated staff grid, reproducing oemer's `extract()` logic.

- `OmrStaffGridAssembler`: **NEW** — orchestrates the full sequence:
  eight-zone staffline extraction/alignment → barline detection → track inference →
  assignment → validation.
- `HoughLineDetector`: wraps `cv2.HoughLinesP` with oemer's exact
  parameters; reproduces its per-axis endpoint reordering.
- `BarlineCandidateFilter`: filters Hough segments by angle (near-vertical)
  and position (within staff envelope).
- `TrackVotingLoop`: **NEW** — infers `num_track` from connected-component
  height/staff-unit ratios, reproducing oemer's threshold loop.
- `StaffTrackGroupAssigner`: **NEW** — assigns track and group IDs to each
  staff segment.
- `StaffGridValidator`: **NEW** — final consistency check across zones for
  Y-center jitter and unit-size (scale) variance.
- `ConnectedComponentBoxExtractor`: 8-connected blob extraction for
  identifying barline segments.
- `NearestStaffUnitSizeResolver`: squared-Euclidean nearest-staff lookup
  for resolution-independent scaling.

---

## 4. Models

Two ONNX models from the [oemer](https://github.com/BreezeWhite/oemer)
project, referenced by `OmrModelSpec`:

| Model | Purpose | Input | Output |
|---|---|---|---|
| `oemer_staff_and_symbols.onnx` | Staff lines + generic symbols | tensor `input`, UINT8, NHWC `[batch, 256, 256, 3]` | tensor `prediction`, FLOAT32, NHWC `[batch, 256, 256, 3]` (3 classes: background / staff / symbols) |
| `oemer_symbol_detail.onnx` | Stems/rests, noteheads, clefs/accidentals | tensor `input`, UINT8, NHWC `[batch, 288, 288, 3]` | tensor `conv2d_25`, FLOAT32, NHWC `[batch, 288, 288, 4]` (4 classes: background / stems+rests / noteheads / clefs+keys) |
| `svm/oemer_clef_svc.onnx` | G/F clef SVC | tensor `input`, FLOAT32 `[batch, 2800]` | integer `label` + float decision scores |
| `svm/oemer_sfn_svc.onnx` | Sharp/flat/natural SVC | tensor `input`, FLOAT32 `[batch, 2800]` | integer `label` + float decision scores |
| `svm/oemer_rests_svc.onnx` | Whole-or-half/quarter/eighth rest SVC | tensor `input`, FLOAT32 `[batch, 2800]` | integer `label` + float decision scores |
| `svm/oemer_rests_above8_svc.onnx` | Eighth/16th/32nd/64th rest SVC | tensor `input`, FLOAT32 `[batch, 2800]` | integer `label` + float decision scores |

These specs were confirmed by directly inspecting the two `.onnx` graph
files with `onnxruntime`'s Python API, not assumed from oemer's
documentation.

**Where they live:** all six runtime models are bundled below
`app/src/main/assets/models/`. The SVM directory also contains the oemer
MIT notice and a manifest with source-wheel, source-pickle, conversion,
and output hashes. Desktop ONNX labels match sklearn on all 384 Phase 4
goldens, Pillow-compatible feature bytes are JVM-verified, and the Android
golden test passes on a OnePlus CPH2707 (Android 16, ARM64).

---

## 5. Current OMR architecture

| Package | Role |
|---|---|
| `data/omr` (root) | OMR contracts, the older decode-through-staff-grid `OnnxOmrEngine` seam, and `DefaultScoreOmrProcessor`, which adapts imported image/PDF pages to the complete smoke-runner/export route. |
| `data/omr/preprocessing` | Decode → canonical resize → BGR conversion → sliding-window tiling → ONNX tensor packing. Pure math split from `Mat`-dependent code wherever possible for JVM testability. |
| `data/omr/inference` | ONNX Runtime session management, real per-tile inference, tile-prediction merging into full-page maps, and argmax class-mask extraction. |
| `data/omr/dewarp` | Staffline geometry detection, gap-bridging, coordinate-map construction, and cubic remap — oemer's `dewarp.py` ported to Kotlin. |
| `data/omr/staffline` | Row-density peak finding and 5-line staff grouping — oemer's `staffline_extraction.py`'s per-zone line-extraction half. |
| `data/omr/track` | Assembled and validated barline/staff-geometry track/system inference — oemer's `staffline_extraction.py` track-inference half plus `bbox.py`. |
| `data/omr/semantic` | Immutable, image-independent semantic score model; evidence-backed measure construction; pitch and accidental resolution; recognition adapters; structured validation and summaries. |
| `di` | Hilt modules: `DatabaseModule`, `DispatcherModule` (qualified `IoDispatcher`/`DefaultDispatcher`/`MainDispatcher`), `OmrModule` (`OrtEnvironment` + `OmrEngine` binding), `RepositoryModule`. |
| `data/local`, `data/repository`, `domain` | Room persistence, file storage, and the `Score`/`ScoreRepository` domain layer — unrelated to OMR, already functional. |
| `data/audio`, `data/practice`, `domain/practice` | Single-stream microphone/YIN analysis, stable onsets, PracticeSequence construction, clock/timing/progression, and bounded informational acoustic-duration tracking. |
| `ui/*` | Functional Library, Preview, read-only Editor notation viewer, and Practice UI; Analysis remains a placeholder. |

Design pattern used throughout the OMR packages: most classes are plain
`object`s (stateless, pure functions) or `@Inject constructor` singletons
with no `@Provides` binding needed, following the pattern set by
`OmrTensorFactory`/`OmrPreprocessor` in Phase 4.2.

---

## 6. OMR pipeline diagram

PDF/JPG/PNG file
│
▼
[Import: ImportScoreUseCase] ✅ implemented
│ (copies file, persists Score row; musicXmlPath left null)
▼
──────────────────────────────────────────────────────────────────
NOT WIRED UP — no code path currently connects Import to OMR
──────────────────────────────────────────────────────────────────
│
▼
[OnnxOmrEngine.recognize()] ⚠️ runs dewarp pipeline, then throws
│
▼ (integrated end-to-end from decode to dewarp)
│
[OmrPreprocessor] ✅ implemented
resize → BGR convert → sliding-window tile
│
▼
[OmrTensorFactory + TileInferenceRunner] ✅ implemented
pack tiles → real ONNX Runtime inference
│
▼
[PredictionMapMerger] ✅ implemented
overlap-average predictions into 2 full-page maps
│
▼
[ClassMaskExtractor] ✅ implemented
argmax → 5 masks: staff, symbols, stemsRests, noteheads, clefsKeys
│
▼
[OmrPageDewarpRunner + ImageMaskAligner] ✅ implemented
aligns image to masks, then runs dewarp
│
▼
[DewarpPipeline] ✅ implemented
morphology → grid detect/group → gap-bridge → mapping → cubic remap
│
▼
[Staffline extraction: ZoneStafflineExtractor] ✅ implemented
row density → z-score → peak find → 5-line group
│
▼
[Staff-grid assembly: OmrStaffGridAssembler]       ✅ implemented
barline detection → track voting → assignment → validation
│
▼
[Notehead extraction] ✅ implemented
│
▼
[Note grouping + group occupancy map] ✅ implemented
│
▼
[Symbol extraction (barlines/clefs/sfns/rests)] ✅ Phase 3 implemented
[Desktop ONNX/sklearn parity] ✅ 384 vectors, zero label mismatches
[Android ONNX golden execution] ✅ OnePlus CPH2707, Android 16, ARM64
│
▼
[Rhythm extraction] ✅ implemented for note groups
[Classified-rest rhythm adapter] ✅ Phase 5 implemented
│
▼
[Semantic score construction] ✅ implemented + smoke-test integrated
│
▼
[MusicXML export via complete smoke-runner route] ✅ implemented
│
▼
Score.musicXmlPath persisted → Editor notation viewer

Independent MusicXML import → Practice Phases 7.0-7.4
(microphone pitch/timing progression + informational acoustic duration)

---

## 7. Remaining OMR work

In dependency order:

1. **Finish Android-native validation** — instrumented tests against real
   OpenCV/ONNX Runtime and real page images.
2. **Unify production entry points** — `DefaultScoreOmrProcessor` already
   runs the complete recognition/export route, while `OnnxOmrEngine.recognize()`
   still ends at the older staff-grid integration seam.
3. **Verify Phase 7.4 calibration acoustically** on real pianos/rooms, especially
   release debounce, low-register decay, legato, and sustain resonance.

---

## 8. Testing

The codebase is verified with **311 passing JVM unit tests**
(`app/src/test/...`). These verify the mathematical correctness of
preprocessing, inference merging, mask extraction, full dewarping logic,
staff identification, track voting, final grid validation, and typed
classified-rest rhythm integration, plus MusicXML, Editor, Preview, and
  Practice behavior. The focused Practice/audio subset contains 79 tests.

| Test file | Covers |
|---|---|
| `preprocessing/CanonicalImageResizerTest` | Target-size computation. |
| `preprocessing/SlidingWindowTilerTest` | Tile-origin computation. |
| `inference/ClassMaskExtractorTest` | Argmax correctness and validation. |
| `dewarp/ImageMaskAlignerTest` | Source-to-mask size reconciliation. |
| `dewarp/StaffMaskMorphologyTest` | Dilate/erode primitives. |
| `dewarp/StafflineGridDetectorTest` | Grid detection and filtering. |
| `dewarp/StafflineGridGrouperTest` | Region-based grid grouping. |
| `dewarp/StafflineGeometryEstimatorTest` | End-to-end geometry estimation. |
| `dewarp/SimpleLinearRegressionTest` | OLS fit/predict correctness. |
| `dewarp/DewarpMappingBuilderTest` | Control-point extraction. |
| `dewarp/DewarpCoordinateInterpolatorTest` | Coordinate map interpolation. |
| `dewarp/DewarpRemapperTest` | Cubic remap and mask thresholding. |
| `dewarp/StafflineGridBridgerTest` | Gap bridging logic. |
| `dewarp/DewarpPipelineTest` | End-to-end pipeline orchestration. |
| `staffline/PeakFinderTest` | Bit-for-bit scipy `find_peaks` regression. |
| `staffline/ZoneStafflineExtractorTest` | End-to-end zone extraction logic. |
| `track/HoughLineDetectorTest` | Endpoint reordering correctness. |
| `track/BarlineCandidateFilterTest` | Angle and staff-envelope filtering. |
| `track/ConnectedComponentBoxExtractorTest` | 8-connected blob extraction. |
| `track/NearestStaffUnitSizeResolverTest` | Nearest-staff lookup logic. |
| `track/TrackVotingLoopTest` | Track-number inference via barline voting. |
| `track/StaffGridValidatorTest` | Grid consistency validation. |
| `grouping/NoteGrouperTest` | Chord grouping and occupancy-map regression. |
| `symbol/SymbolClassifierTest` | Feature shape, class maps, loader caching. |
| `symbol/MusicalBarlineExtractorTest` | Group exclusion and symbol overlap selection. |
| `symbol/RestExtractorTest` | Coarse/refined routing, provenance validation, dot preservation, and rhythm handoff. |
| `symbol/PillowSvmFeatureParityTest` | Exact 40×70 feature bytes against Pillow 11.1.0. |
| `rhythm/RhythmExtractorTest` | Note/rest durations, stems, beams, flags, dots, ambiguity, provenance, and ordering. |
| `semantic/MeasureConstructorTest` | Simple, multiple, pickup, and incomplete-final evidence-backed measures. |
| `semantic/PitchAndAccidentalsTest` | Treble/bass/ledger pitch mapping and key/local/natural/reset accidental state. |
| `semantic/SemanticScoreConstructorTest` | Chords, chord pitches, rests, clef changes, accidentals, geometry regression, and deterministic output. |
| `semantic/SemanticValidatorTest` | Duplicate assignments and unresolved pitch/duration warnings. |

`OemerSvmParityInstrumentedTest` contains 384 class-balanced support/stress
vectors for the four SVMs. It passes on a OnePlus CPH2707 running Android 16
on ARM64: all labels are exact, all scores remain within the `8e-6` sklearn
bound, and the 10 scores that differ from desktop ONNX Runtime differ by
only one ULP (4 coarse-rest and 6 above-eighth-rest scores out of 1,200).

The developer OMR smoke test's rhythm stage emits text-only summaries for
classified-rest count, rest-duration distribution, dotted-rest count, and
unresolved-rest count/reasons in addition to the existing note-group rhythm
fields. Stage 14 adds concise semantic systems, staffs, measures, note,
chord, rest, unresolved-event, and validation-warning counts. It retains no
additional full-resolution debug image.

Remaining validation gaps are primarily Android/device-specific: real-page
OpenCV/ONNX coverage beyond the existing classifier golden, and physical-piano
acoustic calibration for Practice release/sustain behavior.

---

## 9. Build and run instructions

Confirmed from the project's own Gradle configuration:

- **Language/toolchain**: Kotlin 2.0.21, AGP 8.5.2, JVM target 17.
- **`compileSdk`/`targetSdk`**: 35. **`minSdk`**: 25 (Android 7.1+).
- **Build**: standard Gradle Android project —
  `./gradlew assembleDebug` to build.
- **Tests**: `./gradlew testDebugUnitTest` — **311 tests passing**.
- **Model assets**: the two segmentation models and four SVM ONNX exports
  are already under `app/src/main/assets/models/`.

---

## 10. Important technical decisions

- **ONNX Runtime Mobile for inference**, not TensorFlow Lite or a
  server-side model — matches the offline-first requirement and oemer's
  own model format.
- **oemer-compatible preprocessing/inference behavior is a hard
  requirement, not a loose inspiration** — resizing, BGR channel order,
  tiling stride/edge behavior, tile-merge averaging, and (newest)
  barline/track-inference arithmetic are all deliberately bit-for-bit-intent
  matches to oemer's actual Python source, verified by reading the real
  `staffline_extraction.py`/`bbox.py` source directly (not the README's
  simplified prose description, which was found to omit or paraphrase
  several exact formulas during Phase 4.6E).
- **Kotlin/Android-native implementation**, no Python/Chaquopy bridge —
  OpenCV Android SDK + ONNX Runtime Mobile only.
- **Documented deviation: `DewarpCoordinateInterpolator` is not a true
  Delaunay-based `griddata` port.** oemer's actual scattered-point
  interpolation uses `scipy.interpolate.griddata(method='linear')`
  (Delaunay triangulation + barycentric interpolation) over an arbitrary
  point cloud. This project instead exploits the specific structure of
  oemer's own control points (one dense row per detected staffline plus
  two fully-dense boundary rows) to do row-then-column linear
  interpolation. This is exact for that structure but is **not** a
  general-purpose replacement for `griddata`.
- **Documented deviation: `ConnectedComponentBoxExtractor` implements its
  own 8-connected flood fill rather than reusing the dewarp package's
  `ConnectedComponents`.** The latter is deliberately 4-connected (matching
  `scipy.ndimage.label`'s default) and is relied on elsewhere for that
  connectivity; `cv2.findContours` (what oemer's `get_bbox()` actually
  uses) is 8-connected, so reusing the 4-connected utility here would
  silently under-merge diagonally-touching barline candidates. Hole-contour
  semantics of `RETR_TREE` are also knowingly not reproduced (no
  barline-shaped blob realistically has a hole).
- **Phase 4 classifier gate is closed.** Desktop and Android ONNX Runtime
  produce identical labels to sklearn for all 384 deterministic goldens.
  The maximum desktop-to-sklearn score delta is
  `7.736088525500673e-6`. On the OnePlus ARM64 device, 10 of 1,200 scores
  differ from desktop x86-64 by one ULP, with a maximum absolute delta of
  `2.384185791015625e-7`; the instrumented gate enforces a one-ULP maximum.
- **On-device OMR coverage is still focused.** Phase 4 now exercises the
  real ONNX Runtime native library on-device. The OpenCV-backed preprocessing
  and newest `data/omr/track` components still lack device tests using real
  `cv2.HoughLinesP`/`cv2.findContours` calls.
- **`OnnxOmrEngine`/`OmrRepository` are intentionally still stubs.** Their
  `NotImplementedError` throws are not bugs — they're the explicit,
  documented boundary between "components exist" and "pipeline is wired
  up," per each file's own KDoc.
- **`data/omr/track` integration**: The components are fully integrated via
  `OmrStaffGridAssembler`, which performs barline detection, track-number
  voting, segment assignment, and final grid validation.
