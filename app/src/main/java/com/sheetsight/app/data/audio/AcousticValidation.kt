package com.sheetsight.app.data.audio

import com.sheetsight.app.domain.practice.DurationFeedback
import com.sheetsight.app.domain.practice.DurationResult
import com.sheetsight.app.domain.practice.ExpectedArticulation
import com.sheetsight.app.domain.practice.ExpectedDuration
import com.sheetsight.app.domain.practice.ExpectedDurationResolver
import com.sheetsight.app.domain.practice.MonotonicTimeSource
import com.sheetsight.app.domain.practice.MusicalBeat
import com.sheetsight.app.domain.practice.StablePitchEvent
import com.sheetsight.app.domain.practice.PracticeClock
import com.sheetsight.app.domain.practice.PracticeEngine
import com.sheetsight.app.domain.practice.PracticePitch
import com.sheetsight.app.domain.practice.PracticeSequence
import com.sheetsight.app.domain.practice.PracticeSource
import com.sheetsight.app.domain.practice.PracticeStep
import com.sheetsight.app.domain.practice.PracticeTieSemantics
import java.util.Locale

enum class AcousticRegister { LOW, MID, HIGH }

fun PracticePitch.acousticRegister(): AcousticRegister = when (midiNumber) {
    in 0..47 -> AcousticRegister.LOW
    in 48..71 -> AcousticRegister.MID
    else -> AcousticRegister.HIGH
}

enum class ExpectedPhysicalAction {
    NORMAL_RELEASE,
    SHORT_RELEASE,
    HELD,
    PEDAL_SUSTAINED,
    LEGATO_TRANSITION,
    REPEATED_NOTE,
    TIED_CONTINUATION
}

enum class AcousticValidationPlacement { NORMAL, CLOSER, FARTHER }

enum class InferredReleaseState { ACTIVE, RELEASED, SUSTAIN_AMBIGUOUS, UNKNOWN }

enum class AcousticValidationVerdict { PASS, REVIEW, INCONCLUSIVE }

enum class AcousticValidationTestCase(val displayName: String) {
    NORMAL_QUARTER("Normal release - quarter"),
    NORMAL_HALF("Normal release - half"),
    STACCATO("Short release - staccato"),
    TENUTO("Held release - tenuto"),
    FERMATA("Fermata"),
    SUSTAIN_PEDAL("Sustain pedal"),
    LEGATO_LOW("Legato C2-E2-G2"),
    LEGATO("Legato C4-E4-G4"),
    LEGATO_HIGH("Legato C6-E6-G6"),
    REPEATED_IDENTICAL("Repeated C4-C4-C4"),
    TIED_NOTES("C4 quarter tied to C4 half"),
    REST_TRANSITION("Note followed by rest"),
    LOW_REGISTER("Low register C2-E2-G2"),
    MID_REGISTER("Middle register C4-E4-G4"),
    HIGH_REGISTER("High register C6-E6-G6"),
    SOFT_ATTACK("Soft attack release check"),
    NORMAL_ATTACK("Normal attack release check"),
    STRONG_ATTACK("Strong attack release check")
}

data class TargetPitchConfidencePoint(
    val elapsedMillis: Long,
    val confidence: Double,
    val targetPresent: Boolean
)

data class AcousticValidationResult(
    val testCase: AcousticValidationTestCase,
    val pitch: PracticePitch,
    val register: AcousticRegister,
    val expectedAction: ExpectedPhysicalAction,
    val inferredReleaseState: InferredReleaseState,
    val releaseConfidence: Double,
    val sustainAmbiguous: Boolean,
    val observedDurationMillis: Long?,
    val expectedDurationMillis: Long?,
    val passedPolicyExpectation: Boolean,
    val notes: String,
    val onsetTimestampMillis: Long,
    val attackConfidence: Double,
    val initialEnergy: Double,
    val targetPitchConfidenceOverTime: List<TargetPitchConfidencePoint>,
    val dropoutDurationsMillis: List<Long>,
    val residualEnergyDurationMillis: Long,
    val probableAcousticReleaseTimestampMillis: Long?,
    val articulationExpectation: ExpectedArticulation,
    val feedback: DurationFeedback,
    val calibrationQuality: ReleaseCalibrationQuality?,
    val placement: AcousticValidationPlacement
) {
    val verdict: AcousticValidationVerdict
        get() = when {
            passedPolicyExpectation -> AcousticValidationVerdict.PASS
            calibrationQuality == ReleaseCalibrationQuality.POOR &&
                expectedAction in setOf(
                    ExpectedPhysicalAction.NORMAL_RELEASE,
                    ExpectedPhysicalAction.SHORT_RELEASE,
                    ExpectedPhysicalAction.HELD,
                    ExpectedPhysicalAction.PEDAL_SUSTAINED
                ) -> AcousticValidationVerdict.INCONCLUSIVE
            else -> AcousticValidationVerdict.REVIEW
        }
}

data class AcousticValidationLiveNote(
    val pitch: PracticePitch,
    val register: AcousticRegister,
    val onsetTimestampMillis: Long,
    val attackConfidence: Double,
    val initialEnergy: Double,
    val latestTargetPitchConfidence: Double?,
    val currentDropoutMillis: Long,
    val residualEnergyDurationMillis: Long,
    val sustainState: com.sheetsight.app.domain.practice.SustainState,
    val articulationExpectation: ExpectedArticulation,
    val expectedDurationMillis: Long?
)

data class AcousticValidationUpdate(
    val testCase: AcousticValidationTestCase,
    val expectedAction: ExpectedPhysicalAction,
    val currentStepIndex: Int,
    val totalSteps: Int,
    val acceptedOnsetCount: Int,
    val latestDetectedPitch: PracticePitch?,
    val onsetDiagnostics: StablePitchFilterDiagnostics?,
    val liveNotes: List<AcousticValidationLiveNote>,
    val newResults: List<AcousticValidationResult> = emptyList(),
    val allResults: List<AcousticValidationResult> = emptyList()
)

data class AcousticValidationPlan(
    val testCase: AcousticValidationTestCase,
    val defaultAction: ExpectedPhysicalAction,
    val instructions: String,
    val sequence: PracticeSequence
)

/** Repeatable developer matrix. It creates no persistent score or performance record. */
object AcousticValidationTestMatrix {
    val plans: List<AcousticValidationPlan> = AcousticValidationTestCase.entries.map(::planFor)

    fun planFor(testCase: AcousticValidationTestCase): AcousticValidationPlan {
        fun note(
            index: Int,
            pitch: PracticePitch,
            start: Long,
            duration: Long = 1L,
            articulation: ExpectedArticulation = ExpectedArticulation.Normal,
            hasSlur: Boolean = false,
            tie: PracticeTieSemantics = PracticeTieSemantics()
        ) = PracticeStep(
            index = index,
            measureNumber = "1",
            staffs = listOf(1),
            expectedPitches = listOf(pitch),
            sourceNoteIds = listOf("validation-$index"),
            onsetDivisions = start.toInt(),
            startBeat = MusicalBeat.of(start),
            durationBeats = MusicalBeat.of(duration),
            measureBeat = MusicalBeat.of(start),
            tieSemantics = tie,
            expectedArticulation = articulation,
            hasSlur = hasSlur
        )

        fun rest(index: Int, start: Long) = PracticeStep(
            index = index,
            measureNumber = "1",
            staffs = listOf(1),
            expectedPitches = emptyList(),
            sourceNoteIds = emptyList(),
            onsetDivisions = start.toInt(),
            startBeat = MusicalBeat.of(start),
            durationBeats = MusicalBeat.of(1),
            measureBeat = MusicalBeat.of(start),
            isRest = true
        )

        val c2 = PracticePitch('C', 0, 2)
        val e2 = PracticePitch('E', 0, 2)
        val g2 = PracticePitch('G', 0, 2)
        val c4 = PracticePitch('C', 0, 4)
        val e4 = PracticePitch('E', 0, 4)
        val g4 = PracticePitch('G', 0, 4)
        val c6 = PracticePitch('C', 0, 6)
        val e6 = PracticePitch('E', 0, 6)
        val g6 = PracticePitch('G', 0, 6)

        val (action, instructions, steps) = when (testCase) {
            AcousticValidationTestCase.NORMAL_QUARTER -> Triple(
                ExpectedPhysicalAction.NORMAL_RELEASE,
                "Play C4 for about one quarter note, release the key normally, and do not use pedal.",
                listOf(note(0, c4, 0))
            )
            AcousticValidationTestCase.NORMAL_HALF -> Triple(
                ExpectedPhysicalAction.NORMAL_RELEASE,
                "Play C4 for about one half note, release the key normally, and do not use pedal.",
                listOf(note(0, c4, 0, duration = 2))
            )
            AcousticValidationTestCase.STACCATO -> Triple(
                ExpectedPhysicalAction.SHORT_RELEASE,
                "Play the staccato C4 clearly short. Do not use pedal.",
                listOf(note(0, c4, 0, articulation = ExpectedArticulation.Staccato))
            )
            AcousticValidationTestCase.TENUTO -> Triple(
                ExpectedPhysicalAction.HELD,
                "Hold the tenuto C4 for approximately its full written duration, then release normally.",
                listOf(note(0, c4, 0, articulation = ExpectedArticulation.Tenuto))
            )
            AcousticValidationTestCase.FERMATA -> Triple(
                ExpectedPhysicalAction.HELD,
                "Hold C4 beyond its ordinary duration, then release. The fermata duration is flexible.",
                listOf(note(0, c4, 0, articulation = ExpectedArticulation.Fermata))
            )
            AcousticValidationTestCase.SUSTAIN_PEDAL -> Triple(
                ExpectedPhysicalAction.PEDAL_SUSTAINED,
                "Strike C4, release the physical key, keep sustain pedal down, and let the sound continue.",
                listOf(note(0, c4, 0))
            )
            AcousticValidationTestCase.LEGATO_LOW -> Triple(
                ExpectedPhysicalAction.LEGATO_TRANSITION,
                "Play C2-E2-G2 legato with intentional acoustic overlap and no repeated attacks.",
                listOf(note(0, c2, 0, hasSlur = true), note(1, e2, 1, hasSlur = true), note(2, g2, 2, hasSlur = true))
            )
            AcousticValidationTestCase.LEGATO -> Triple(
                ExpectedPhysicalAction.LEGATO_TRANSITION,
                "Play C4-E4-G4 legato with intentional acoustic overlap and no repeated attacks.",
                listOf(note(0, c4, 0, hasSlur = true), note(1, e4, 1, hasSlur = true), note(2, g4, 2, hasSlur = true))
            )
            AcousticValidationTestCase.LEGATO_HIGH -> Triple(
                ExpectedPhysicalAction.LEGATO_TRANSITION,
                "Play C6-E6-G6 legato with intentional acoustic overlap and no repeated attacks.",
                listOf(note(0, c6, 0, hasSlur = true), note(1, e6, 1, hasSlur = true), note(2, g6, 2, hasSlur = true))
            )
            AcousticValidationTestCase.REPEATED_IDENTICAL -> Triple(
                ExpectedPhysicalAction.REPEATED_NOTE,
                "Play three separate C4 attacks. A single sustained attack must not complete the case.",
                listOf(note(0, c4, 0), note(1, c4, 1), note(2, c4, 2))
            )
            AcousticValidationTestCase.TIED_NOTES -> {
                val group = "validation-tie-c4"
                Triple(
                    ExpectedPhysicalAction.TIED_CONTINUATION,
                    "Play one C4 attack and sustain it across a quarter tied to a half. Do not restrike.",
                    listOf(
                        note(
                            0,
                            c4,
                            0,
                            tie = PracticeTieSemantics(
                                groupId = group,
                                tieStart = true,
                                combinedExpectedDurationBeats = MusicalBeat.of(3)
                            )
                        ),
                        note(
                            1,
                            c4,
                            1,
                            duration = 2,
                            tie = PracticeTieSemantics(groupId = group, tieContinuation = true, tieEnd = true)
                        )
                    )
                )
            }
            AcousticValidationTestCase.REST_TRANSITION -> Triple(
                ExpectedPhysicalAction.NORMAL_RELEASE,
                "Play C4, release normally, and remain silent through the following rest while residual sound decays.",
                listOf(note(0, c4, 0), rest(1, 1))
            )
            AcousticValidationTestCase.LOW_REGISTER -> Triple(
                ExpectedPhysicalAction.NORMAL_RELEASE,
                "Play C2-E2-G2 as separate normal releases without pedal.",
                listOf(note(0, c2, 0), note(1, e2, 1), note(2, g2, 2))
            )
            AcousticValidationTestCase.MID_REGISTER -> Triple(
                ExpectedPhysicalAction.NORMAL_RELEASE,
                "Play C4-E4-G4 as separate normal releases without pedal.",
                listOf(note(0, c4, 0), note(1, e4, 1), note(2, g4, 2))
            )
            AcousticValidationTestCase.HIGH_REGISTER -> Triple(
                ExpectedPhysicalAction.NORMAL_RELEASE,
                "Play C6-E6-G6 as separate normal releases without pedal.",
                listOf(note(0, c6, 0), note(1, e6, 1), note(2, g6, 2))
            )
            AcousticValidationTestCase.SOFT_ATTACK -> Triple(
                ExpectedPhysicalAction.NORMAL_RELEASE,
                "Play a clearly audible soft C4 and release normally. This checks release stability only.",
                listOf(note(0, c4, 0))
            )
            AcousticValidationTestCase.NORMAL_ATTACK -> Triple(
                ExpectedPhysicalAction.NORMAL_RELEASE,
                "Play C4 at a normal attack level and release normally.",
                listOf(note(0, c4, 0))
            )
            AcousticValidationTestCase.STRONG_ATTACK -> Triple(
                ExpectedPhysicalAction.NORMAL_RELEASE,
                "Play a strong C4 attack and release normally. This checks release stability only.",
                listOf(note(0, c4, 0))
            )
        }
        return AcousticValidationPlan(
            testCase = testCase,
            defaultAction = action,
            instructions = instructions,
            sequence = PracticeSequence(PracticeSource("acoustic-validation", 1), steps)
        )
    }
}

/**
 * Developer-only real-device harness over the production pitch, onset, progression, clock, and
 * release components. It retains only bounded derived summaries and never stores PCM.
 */
class AcousticValidationSession(
    val plan: AcousticValidationPlan,
    val expectedAction: ExpectedPhysicalAction = plan.defaultAction,
    val placement: AcousticValidationPlacement = AcousticValidationPlacement.NORMAL,
    calibrationProfile: ReleaseCalibrationProfile? = null,
    private val bpm: Int = 60,
    private val maximumResults: Int = 64
) {
    private val time = FrameTimeSource()
    private val engine = PracticeEngine(clock = PracticeClock(time))
    private val stabilityFilter = StablePitchFilter()
    private val observations = linkedMapOf<Int, MutableObservation>()
    private val results = ArrayDeque<AcousticValidationResult>()
    private val tracker = AcousticNoteEventTracker(
        diagnosticObserver = AcousticReleaseDiagnosticObserver(::onReleaseDiagnostic)
    )
    private val calibrationQuality = calibrationProfile?.takeIf { it.isCompatible }?.quality
    private var started = false
    private var acceptedOnsetCount = 0
    private var latestDetectedPitch: PracticePitch? = null

    init {
        require(maximumResults > 0)
        tracker.applyCalibrationProfile(calibrationProfile)
        engine.load(plan.sequence)
        engine.setTempo(bpm)
    }

    fun process(frame: PitchFrame): AcousticValidationUpdate {
        time.now = frame.timestampMillis
        if (!started) {
            engine.start()
            started = true
        }
        engine.onClockTick()

        val newResults = mutableListOf<AcousticValidationResult>()
        complete(tracker.process(frame).completed, newResults)
        val event = stabilityFilter.process(frame)
        if (event != null) {
            if (event is StablePitchEvent.Stable && event.isNewOnset) {
                latestDetectedPitch = event.pitch.nearestPitch
                complete(
                    tracker.onNewOnset(event.pitch.nearestPitch, event.pitch.timestampMillis).completed,
                    newResults
                )
            }
            val before = engine.progress
            val after = engine.onPitchEvent(event)
            if (
                event is StablePitchEvent.Stable &&
                after.currentStepIndex > before.currentStepIndex &&
                before.currentStep?.isRest == false
            ) {
                val step = requireNotNull(before.currentStep)
                val pitch = event.pitch.nearestPitch
                observations[step.index] = MutableObservation(
                    step = step,
                    pitch = pitch,
                    onset = event.pitch.timestampMillis,
                    attackConfidence = event.pitch.confidence,
                    initialEnergy = event.pitch.signalLevel,
                    expected = ExpectedDurationResolver.resolve(step, before.tempo.bpm)
                )
                acceptedOnsetCount++
                complete(
                    tracker.acceptNote(step, pitch, event.pitch.timestampMillis, before.tempo.bpm).completed,
                    newResults
                )
            }
        }
        return update(newResults)
    }

    fun snapshot(): AcousticValidationUpdate = update(emptyList())

    private fun complete(completed: List<DurationResult>, destination: MutableList<AcousticValidationResult>) {
        completed.forEach { duration ->
            val observation = observations.remove(duration.stepIndex) ?: return@forEach
            val releaseAt = duration.observedEvent.releaseTimeMillis ?: observation.lastDiagnosticAt ?: observation.onset
            observation.closeOpenIntervals(releaseAt)
            val inferred = when {
                duration.feedback == DurationFeedback.SustainAmbiguous -> InferredReleaseState.SUSTAIN_AMBIGUOUS
                duration.observedEvent.releaseTimeMillis != null -> InferredReleaseState.RELEASED
                else -> InferredReleaseState.UNKNOWN
            }
            val passed = passesExpectation(duration, inferred)
            val result = AcousticValidationResult(
                testCase = plan.testCase,
                pitch = duration.pitch,
                register = duration.pitch.acousticRegister(),
                expectedAction = expectedAction,
                inferredReleaseState = inferred,
                releaseConfidence = duration.observedEvent.releaseConfidence,
                sustainAmbiguous = duration.feedback == DurationFeedback.SustainAmbiguous,
                observedDurationMillis = duration.observedEvent.observedDurationMillis,
                expectedDurationMillis = duration.expectedDuration?.milliseconds,
                passedPolicyExpectation = passed,
                notes = resultNotes(duration, passed),
                onsetTimestampMillis = observation.onset,
                attackConfidence = observation.attackConfidence,
                initialEnergy = observation.initialEnergy,
                targetPitchConfidenceOverTime = observation.confidenceTimeline.toList(),
                dropoutDurationsMillis = observation.dropoutDurations.toList(),
                residualEnergyDurationMillis = observation.residualEnergyDuration,
                probableAcousticReleaseTimestampMillis = duration.observedEvent.releaseTimeMillis,
                articulationExpectation = duration.expectedDuration?.articulation ?: observation.step.expectedArticulation,
                feedback = duration.feedback,
                calibrationQuality = calibrationQuality,
                placement = placement
            )
            results.addLast(result)
            while (results.size > maximumResults) results.removeFirst()
            destination += result
        }
    }

    private fun onReleaseDiagnostic(diagnostic: AcousticReleaseFrameDiagnostic) {
        observations[diagnostic.stepIndex]?.accept(diagnostic)
    }

    private fun passesExpectation(result: DurationResult, inferred: InferredReleaseState): Boolean {
        val feedback = result.feedback
        if (
            calibrationQuality == ReleaseCalibrationQuality.POOR &&
            expectedAction in setOf(
                ExpectedPhysicalAction.NORMAL_RELEASE,
                ExpectedPhysicalAction.SHORT_RELEASE,
                ExpectedPhysicalAction.HELD,
                ExpectedPhysicalAction.PEDAL_SUSTAINED
            )
        ) {
            // Unknown feedback under a POOR profile is conservative behavior, not validation
            // evidence. Calling it PASS hid bad calibration in every supplied device run.
            return false
        }
        return when (expectedAction) {
            ExpectedPhysicalAction.NORMAL_RELEASE -> inferred == InferredReleaseState.RELEASED &&
                feedback !in setOf(DurationFeedback.SustainAmbiguous, DurationFeedback.Unknown)
            ExpectedPhysicalAction.SHORT_RELEASE -> feedback in setOf(
                DurationFeedback.StaccatoConsistent,
                DurationFeedback.ApproximatelyCorrect
            )
            ExpectedPhysicalAction.HELD -> feedback !in setOf(
                DurationFeedback.TooShort,
                DurationFeedback.PossiblyShort,
                DurationFeedback.ArticulationInconsistent
            )
            ExpectedPhysicalAction.PEDAL_SUSTAINED -> feedback in setOf(
                DurationFeedback.SustainAmbiguous,
                DurationFeedback.Unknown
            )
            ExpectedPhysicalAction.LEGATO_TRANSITION -> feedback != DurationFeedback.Long
            ExpectedPhysicalAction.REPEATED_NOTE ->
                result.observedEvent.releaseCause == com.sheetsight.app.domain.practice.AcousticReleaseCause.ReplacedByNewOnset &&
                    inferred == InferredReleaseState.RELEASED
            ExpectedPhysicalAction.TIED_CONTINUATION -> acceptedOnsetCount == 1 && inferred == InferredReleaseState.RELEASED
        }
    }

    private fun resultNotes(result: DurationResult, passed: Boolean): String = buildString {
        append(
            when {
                passed -> "Policy expectation met"
                calibrationQuality == ReleaseCalibrationQuality.POOR ->
                    "Inconclusive release policy: stored calibration is POOR"
                else -> "Review against manual label"
            }
        )
        append("; releaseCause=")
        append(result.observedEvent.releaseCause ?: "unresolved")
        append("; progression=")
        append(engine.progress.currentStepIndex)
        append('/')
        append(engine.progress.totalSteps)
        if (plan.testCase == AcousticValidationTestCase.REPEATED_IDENTICAL) {
            append("; separateAcceptedOnsets=")
            append(acceptedOnsetCount)
        }
        if (plan.testCase == AcousticValidationTestCase.TIED_NOTES) {
            append("; tieAcceptedOnsets=")
            append(acceptedOnsetCount)
        }
    }

    private fun update(newResults: List<AcousticValidationResult>): AcousticValidationUpdate = AcousticValidationUpdate(
        testCase = plan.testCase,
        expectedAction = expectedAction,
        currentStepIndex = engine.progress.currentStepIndex,
        totalSteps = engine.progress.totalSteps,
        acceptedOnsetCount = acceptedOnsetCount,
        latestDetectedPitch = latestDetectedPitch,
        onsetDiagnostics = stabilityFilter.latestDiagnostics,
        liveNotes = observations.values.map(MutableObservation::live),
        newResults = newResults,
        allResults = results.toList()
    )

    private data class FrameTimeSource(var now: Long = 0L) : MonotonicTimeSource {
        override fun nowMillis(): Long = now
    }

    private data class MutableObservation(
        val step: PracticeStep,
        val pitch: PracticePitch,
        val onset: Long,
        val attackConfidence: Double,
        val initialEnergy: Double,
        val expected: ExpectedDuration?,
        val confidenceTimeline: ArrayDeque<TargetPitchConfidencePoint> = ArrayDeque(),
        val dropoutDurations: ArrayDeque<Long> = ArrayDeque(),
        var dropoutStartedAt: Long? = null,
        var residualEnergyDuration: Long = 0L,
        var previousDiagnosticAt: Long? = null,
        var previousResidualEnergy: Boolean = false,
        var lastDiagnosticAt: Long? = null,
        var latestConfidence: Double? = null,
        var latestState: com.sheetsight.app.domain.practice.SustainState =
            com.sheetsight.app.domain.practice.SustainState.Onset,
        var lastTimelineAt: Long? = null
    ) {
        fun accept(diagnostic: AcousticReleaseFrameDiagnostic) {
            latestConfidence = diagnostic.targetPitchConfidence
            latestState = diagnostic.sustainState
            val previousAt = previousDiagnosticAt
            if (previousAt != null && previousResidualEnergy) {
                residualEnergyDuration += (diagnostic.timestampMillis - previousAt).coerceAtLeast(0L)
            }
            previousDiagnosticAt = diagnostic.timestampMillis
            previousResidualEnergy = diagnostic.residualEnergyPresent
            lastDiagnosticAt = diagnostic.timestampMillis

            if (!diagnostic.targetPitchPresent && dropoutStartedAt == null) {
                dropoutStartedAt = diagnostic.timestampMillis
            } else if (diagnostic.targetPitchPresent) {
                closeDropout(diagnostic.timestampMillis)
            }
            if (lastTimelineAt == null || diagnostic.timestampMillis - requireNotNull(lastTimelineAt) >= TIMELINE_INTERVAL_MS) {
                confidenceTimeline.addLast(
                    TargetPitchConfidencePoint(
                        elapsedMillis = (diagnostic.timestampMillis - onset).coerceAtLeast(0L),
                        confidence = diagnostic.targetPitchConfidence ?: 0.0,
                        targetPresent = diagnostic.targetPitchPresent
                    )
                )
                while (confidenceTimeline.size > MAX_TIMELINE_POINTS) confidenceTimeline.removeFirst()
                lastTimelineAt = diagnostic.timestampMillis
            }
        }

        fun closeOpenIntervals(at: Long) {
            val previousAt = previousDiagnosticAt
            if (previousAt != null && previousResidualEnergy) {
                residualEnergyDuration += (at - previousAt).coerceAtLeast(0L)
            }
            closeDropout(at)
        }

        fun live(): AcousticValidationLiveNote = AcousticValidationLiveNote(
            pitch = pitch,
            register = pitch.acousticRegister(),
            onsetTimestampMillis = onset,
            attackConfidence = attackConfidence,
            initialEnergy = initialEnergy,
            latestTargetPitchConfidence = latestConfidence,
            currentDropoutMillis = dropoutStartedAt?.let { start ->
                ((lastDiagnosticAt ?: start) - start).coerceAtLeast(0L)
            } ?: 0L,
            residualEnergyDurationMillis = residualEnergyDuration,
            sustainState = latestState,
            articulationExpectation = expected?.articulation ?: step.expectedArticulation,
            expectedDurationMillis = expected?.milliseconds
        )

        private fun closeDropout(at: Long) {
            dropoutStartedAt?.let { start ->
                dropoutDurations.addLast((at - start).coerceAtLeast(0L))
                while (dropoutDurations.size > MAX_DROPOUTS) dropoutDurations.removeFirst()
            }
            dropoutStartedAt = null
        }

        private companion object {
            const val TIMELINE_INTERVAL_MS = 100L
            const val MAX_TIMELINE_POINTS = 64
            const val MAX_DROPOUTS = 32
        }
    }
}

fun AcousticValidationResult.compactLine(): String = String.format(
    Locale.US,
    "%s | %s | %s | %s | conf %.2f | observed %s ms | expected %s ms | %s",
    testCase.displayName,
    pitch.displayName,
    register,
    inferredReleaseState,
    releaseConfidence,
    observedDurationMillis?.toString() ?: "?",
    expectedDurationMillis?.toString() ?: "?",
    verdict
)
