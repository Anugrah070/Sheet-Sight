package com.sheetsight.app.domain.practice

import com.sheetsight.app.data.audio.NoteOnsetEvidence
import com.sheetsight.app.data.audio.StablePitchEvent
import kotlin.math.roundToLong

/** Sole owner of practice timing, advancement, rests, and repeated-note re-arming. */
class PracticeEngine(
    private val matcher: PracticeTimingMatcher = PracticeTimingMatcher(),
    val clock: PracticeClock = PracticeClock()
) {
    var progress: PracticeProgress = PracticeProgress()
        private set

    private var armed = true
    private var consumedMidi: Int? = null
    private var restEnteredBeat: Double? = null

    fun loading(): PracticeProgress = update(
        PracticeProgress(
            phase = PracticePhase.Loading,
            tempo = progress.tempo,
            countInEnabled = progress.countInEnabled
        )
    )

    fun load(sequence: PracticeSequence, userDefaultBpm: Int = progress.tempo.bpm): PracticeProgress {
        resetRuntime()
        val detected = sequence.source.detectedTempoBpm
        val tempo = if (detected != null) {
            PracticeTempo(detected, PracticeTempoSource.Detected)
        } else {
            PracticeTempo(userDefaultBpm.coerceIn(MIN_PRACTICE_BPM, MAX_PRACTICE_BPM), PracticeTempoSource.UserDefault)
        }
        return if (sequence.steps.isEmpty()) {
            update(
                PracticeProgress(
                    phase = PracticePhase.Error,
                    sequence = sequence,
                    tempo = tempo,
                    countInEnabled = progress.countInEnabled,
                    errorMessage = "This MusicXML contains no supported notes or rests."
                )
            )
        } else {
            update(
                PracticeProgress(
                    phase = PracticePhase.Ready,
                    sequence = sequence,
                    tempo = tempo,
                    countInEnabled = progress.countInEnabled
                )
            )
        }
    }

    fun fail(message: String): PracticeProgress {
        clock.stop()
        return update(
            progress.copy(
                phase = PracticePhase.Error,
                errorMessage = message,
                matchState = MatchState.Waiting,
                countInRemaining = null
            )
        )
    }

    /** Immediate start used by the controller after count-in and by deterministic tests. */
    fun start(): PracticeProgress {
        if (progress.phase == PracticePhase.Ready) startClockAndListen()
        return progress
    }

    fun beginCountIn(): PracticeProgress {
        if (progress.phase != PracticePhase.Ready) return progress
        val beats = countInPulseCount()
        return update(
            progress.copy(
                phase = PracticePhase.CountIn,
                countInRemaining = beats,
                matchState = MatchState.Waiting,
                timingOffsetMillis = null,
                errorMessage = null
            )
        )
    }

    fun updateCountIn(remaining: Int): PracticeProgress {
        if (progress.phase == PracticePhase.CountIn) {
            update(progress.copy(countInRemaining = remaining.coerceAtLeast(1)))
        }
        return progress
    }

    fun completeCountIn(): PracticeProgress {
        if (progress.phase == PracticePhase.CountIn) startClockAndListen()
        return progress
    }

    fun pause(): PracticeProgress {
        if (progress.phase == PracticePhase.Listening) {
            clock.pause()
            update(progress.copy(phase = PracticePhase.Paused, matchState = MatchState.Waiting))
        }
        return progress
    }

    fun resume(): PracticeProgress {
        if (progress.phase == PracticePhase.Paused) {
            clock.resume(progress.tempo.bpm)
            update(progress.copy(phase = PracticePhase.Listening, matchState = MatchState.Waiting))
        }
        return progress
    }

    fun stop(): PracticeProgress {
        if (progress.phase in ACTIVE_PHASES || progress.phase == PracticePhase.Completed) {
            resetRuntime()
            update(
                progress.copy(
                    phase = PracticePhase.Ready,
                    currentStepIndex = 0,
                    lastDetectedPitch = null,
                    matchState = MatchState.Waiting,
                    timingOffsetMillis = null,
                    countInRemaining = null,
                    restViolationCount = 0
                )
            )
        }
        return progress
    }

    fun setTempo(bpm: Int): PracticeProgress {
        if (progress.phase !in setOf(PracticePhase.Ready, PracticePhase.Paused, PracticePhase.Completed)) return progress
        val tempo = PracticeTempo(
            bpm = bpm.coerceIn(MIN_PRACTICE_BPM, MAX_PRACTICE_BPM),
            source = PracticeTempoSource.UserDefault
        )
        update(progress.copy(tempo = tempo))
        return progress
    }

    fun setCountInEnabled(enabled: Boolean): PracticeProgress {
        if (progress.phase in setOf(PracticePhase.Ready, PracticePhase.Paused, PracticePhase.Completed)) {
            update(progress.copy(countInEnabled = enabled))
        }
        return progress
    }

    fun countInPulseCount(): Int = progress.sequence?.source?.initialMeter?.beats ?: DEFAULT_COUNT_IN_BEATS

    fun countInPulseMillis(): Long {
        val pulseBeats = progress.sequence?.source?.initialMeter?.pulseBeats ?: MusicalBeat.of(1)
        return (60_000.0 * pulseBeats.toDouble() / progress.tempo.bpm).roundToLong().coerceAtLeast(1L)
    }

    fun onPitchEvent(event: StablePitchEvent): PracticeProgress {
        if (progress.phase != PracticePhase.Listening) return progress
        val step = progress.currentStep ?: return progress
        if (step.tieContinuation) {
            // A genuine tie continues the original sounding event; microphone frames have no
            // authority to require or synthesize a second attack for this source note.
            return progress
        }
        if (step.isRest) {
            if (event is StablePitchEvent.Stable) {
                update(
                    progress.copy(
                        lastDetectedPitch = event.pitch,
                        matchState = MatchState.RestViolation,
                        timingOffsetMillis = null,
                        restViolationCount = progress.restViolationCount + 1
                    )
                )
            }
            return progress
        }

        when (event) {
            StablePitchEvent.Release -> {
                armed = true
                consumedMidi = null
                update(progress.copy(matchState = MatchState.Waiting, timingOffsetMillis = null))
            }
            is StablePitchEvent.LowConfidence -> update(
                progress.copy(
                    lastDetectedPitch = event.pitch,
                    matchState = MatchState.LowConfidence,
                    timingOffsetMillis = null
                )
            )
            is StablePitchEvent.Stable -> handleStable(event)
        }
        return progress
    }

    /** Called by a low-frequency controller ticker; only rests can advance here. */
    fun onClockTick(): PracticeProgress {
        if (progress.phase != PracticePhase.Listening) return progress
        val step = progress.currentStep ?: return progress
        val nowBeat = clock.currentBeat()
        if (step.tieContinuation) {
            val endBeat = step.startBeat?.let { start -> step.durationBeats?.let(start::plus) }
            if (endBeat != null && nowBeat >= endBeat.toDouble()) {
                val tiedMidi = step.expectedPitches.singleOrNull()?.midiNumber
                advanceOne(
                    result = PracticeMatchResult(MatchState.TieContinuation),
                    onsetBeat = nowBeat,
                    consumedPitchMidi = tiedMidi
                )
                // The first untied note after a tie chain must still present a genuine onset.
                if (progress.currentStep?.tieContinuation != true && progress.currentStep?.isRest != true) {
                    armed = false
                    consumedMidi = tiedMidi
                }
            }
        } else if (step.isRest) {
            if (!step.isTimingResolved || step.durationBeats == null) {
                advanceOne(
                    result = PracticeMatchResult(MatchState.Unsupported),
                    onsetBeat = nowBeat,
                    consumedPitchMidi = null
                )
                return progress
            }
            val entered = restEnteredBeat ?: nowBeat.also { restEnteredBeat = it }
            if (nowBeat - entered >= step.durationBeats.toDouble()) {
                advanceOne(
                    result = PracticeMatchResult(MatchState.RestComplete),
                    onsetBeat = nowBeat,
                    consumedPitchMidi = null
                )
            }
        } else if (
            progress.matchState in setOf(MatchState.Waiting, MatchState.LowConfidence, MatchState.Missed) &&
            matcher.isMissed(step, nowBeat, progress.tempo.bpm)
        ) {
            update(progress.copy(matchState = MatchState.Missed, timingOffsetMillis = null))
        }
        return progress
    }

    private fun handleStable(event: StablePitchEvent.Stable) {
        val step = progress.currentStep ?: return
        val detected = event.pitch
        if (!armed) {
            val repeatedExpected = step.expectedPitches.singleOrNull()?.midiNumber == consumedMidi
            val validRetrigger = repeatedExpected && event.onsetEvidence == NoteOnsetEvidence.AmplitudeRise
            val differentOnset = !repeatedExpected && event.isNewOnset &&
                detected.nearestPitch.midiNumber != consumedMidi
            if (!validRetrigger && !differentOnset) {
                update(
                    progress.copy(
                        lastDetectedPitch = detected,
                        matchState = MatchState.Waiting,
                        timingOffsetMillis = null
                    )
                )
                return
            }
            armed = true
            consumedMidi = null
        }

        val actualBeat = clock.beatAt(detected.timestampMillis)
        val result = matcher.match(step, detected, actualBeat, progress.tempo.bpm)
        if (result.state.advancesPlayableNote) {
            advanceOne(result, actualBeat, detected.nearestPitch.midiNumber, detected)
        } else {
            update(
                progress.copy(
                    lastDetectedPitch = detected,
                    matchState = result.state,
                    timingOffsetMillis = result.timingOffsetMillis
                )
            )
        }
    }

    private fun advanceOne(
        result: PracticeMatchResult,
        onsetBeat: Double,
        consumedPitchMidi: Int?,
        detectedPitch: DetectedPitch? = progress.lastDetectedPitch
    ) {
        val nextIndex = (progress.currentStepIndex + 1).coerceAtMost(progress.totalSteps)
        armed = consumedPitchMidi == null
        consumedMidi = consumedPitchMidi
        restEnteredBeat = null
        val completed = nextIndex == progress.totalSteps
        if (!completed && progress.sequence?.steps?.getOrNull(nextIndex)?.isRest == true) {
            restEnteredBeat = onsetBeat
            armed = true
            consumedMidi = null
        }
        if (completed) clock.pause()
        update(
            progress.copy(
                phase = if (completed) PracticePhase.Completed else PracticePhase.Listening,
                currentStepIndex = nextIndex,
                lastDetectedPitch = detectedPitch,
                matchState = result.state,
                timingOffsetMillis = result.timingOffsetMillis,
                countInRemaining = null
            )
        )
    }

    private fun startClockAndListen() {
        armed = true
        consumedMidi = null
        restEnteredBeat = null
        clock.start(progress.tempo.bpm)
        if (progress.currentStep?.isRest == true) restEnteredBeat = clock.currentBeat()
        update(
            progress.copy(
                phase = PracticePhase.Listening,
                matchState = MatchState.Waiting,
                timingOffsetMillis = null,
                countInRemaining = null,
                errorMessage = null
            )
        )
    }

    private fun resetRuntime() {
        armed = true
        consumedMidi = null
        restEnteredBeat = null
        clock.stop()
    }

    private fun update(value: PracticeProgress): PracticeProgress {
        check(value.currentStepIndex in 0..value.totalSteps) { "Practice index escaped sequence bounds." }
        progress = value
        return value
    }

    private companion object {
        const val DEFAULT_COUNT_IN_BEATS = 4
        val ACTIVE_PHASES = setOf(
            PracticePhase.CountIn,
            PracticePhase.Listening,
            PracticePhase.Paused
        )
    }
}
