# Sheet Sight

Offline Android app: import PDF/image sheet music → OMR to editable
MusicXML → correct in an editor → practice note-by-note against real piano
input via live pitch detection.

Full functional scope lives in the project's `ProjectRequirements.md`. This
README covers the codebase itself.

## Status

**Phase 1 — Project Skeleton.** No features are implemented yet. This
establishes the architecture, navigation shell, DI graph, and empty tab
screens that every later phase builds on.

## Stack

| Concern              | Choice                                   |
|-----------------------|-------------------------------------------|
| Language               | Kotlin                                    |
| UI                     | Jetpack Compose (Material 3)              |
| Architecture           | MVVM + Repository Pattern                 |
| DI                     | Hilt                                      |
| Navigation             | Navigation Compose                        |
| Local persistence      | Room                                      |
| Async                  | Kotlin Coroutines + Flow                  |
| OMR (Phase 4)          | ONNX Runtime Mobile + OpenCV Android SDK (oemer models) |
| Pitch detection (Ph.6) | TarsosDSP                                 |

Entirely offline — no network calls, no cloud dependency, no AI API calls
in the finished app.

## Getting started

1. Open the project root in Android Studio (Koala or newer recommended for
   AGP 8.5 / Kotlin 2.0).
2. Let Gradle sync. Android Studio will fetch `gradle-wrapper.jar`
   automatically on first sync if it isn't already present locally.
3. Run the `app` configuration on a device/emulator running API 25+
   (Android 7.1, Nougat, or later).

Minimum SDK is 25 to satisfy the "above Nougat" requirement; compile/target
SDK is 35.

## Package structure

See the architecture section of the project's design docs, or the inline
KDoc in `ui/navigation/Destination.kt` and `di/RepositoryModule.kt` for how
new features should be wired in (one tab route + one Hilt binding per
feature, following the existing pattern).

## Conventions

- ViewModels expose `StateFlow<UiState>`, never mutable state directly.
- Repository interfaces live in `domain/repository`; implementations in
  `data/repository`; Hilt binds them in `di/RepositoryModule.kt`.
- No hardcoded user-facing strings — everything goes through
  `res/values/strings.xml`.
- Every placeholder/temporary class in this skeleton is commented with
  which phase replaces it and what to delete when that happens.
