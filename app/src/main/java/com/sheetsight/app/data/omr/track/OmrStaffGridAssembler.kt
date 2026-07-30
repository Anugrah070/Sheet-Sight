package com.sheetsight.app.data.omr.track
import android.util.Log
import com.sheetsight.app.data.omr.dewarp.DewarpedPage
import com.sheetsight.app.data.omr.inference.OmrClassMasks
import com.sheetsight.app.data.omr.staffline.ZoneStaff
import com.sheetsight.app.data.omr.staffline.ZoneStafflineExtractor
/**
 * Orchestrates the transition from dewarped pixel masks to a structured,
 * validated staff grid. Combines per-zone staffline extraction, barline
 * detection, track voting, and final grid validation.
 *
 * **Barline detection (corrected).** The previous implementation ran
 * `HoughLinesP` on the barline-candidate mask, rasterized the filtered
 * Hough segments as boxes into a *new* mask, then ran connected-component
 * extraction on that rasterized mask — meaning that if `HoughLinesP`
 * returned zero segments (common for sparse, fragmented, or noisy masks),
 * zero barline boxes were produced, even though the candidate mask
 * contained dozens of barline blobs.
 *
 * oemer's actual `further_infer_track_nums()` runs `get_bbox()` (i.e.
 * connected-component extraction) **directly on the barline residual
 * mask**, then uses Hough lines only as a secondary filter to discard
 * bboxes that don't align with a detected near-vertical segment. This
 * class now reproduces that flow: [ConnectedComponentBoxExtractor] on the
 * candidate mask first, then intersection-filtered against the Hough line
 * x-range for each surviving near-vertical segment. If no Hough segments
 * survive, all connected-component boxes are passed through — matching
 * the graceful-degradation this pipeline requires (Hough line detection
 * is inherently sensitive to mask density and noise; its failure should
 * not suppress a page's entire barline detection).
 */
object OmrStaffGridAssembler {
    private const val TAG = "OmrGrid"
    fun assemble(page: DewarpedPage): ValidatedStaffGridResult {
        val masks = page.masks
        val width = page.width
        val height = page.height
        val zoneResult = ZoneStafflineExtractor.extract(masks.staff, width, height, zoneLeft = 0, zoneRight = width)
        val zoneStaffGrid: List<List<ZoneStaff>> = listOf(zoneResult.staffs)
        Log.d(TAG, "[OMR_GRID] staffs=${zoneResult.staffs.size} page=${width}x$height")
        if (zoneResult.staffs.isEmpty()) {
            return ValidatedStaffGridResult(
                page = page,
                trackVote = TrackVotingLoop.infer(BooleanArray(width * height), width, height, emptyList()),
                validatedGrid = emptyList()
            )
        }
        val staffCenterGrid: List<List<StaffCenterInfo>> = zoneStaffGrid.map { zone ->
            zone.map { staff ->
                StaffCenterInfo(
                    xCenter = (zoneResult.zoneLeft + zoneResult.zoneRight) / 2.0,
                    yCenter = staff.yCenter,
                    unitSize = staff.unitSize
                )
            }
        }
        val staffBounds = zoneResult.staffs.map { staff ->
            StaffBounds(
                yUpper = staff.lines.minOf { it.yUpper },
                yLower = staff.lines.maxOf { it.yLower },
                xLeft = staff.lines.minOf { it.xLeft },
                xRight = staff.lines.maxOf { it.xRight }
            )
        }
        // --- Diagnostic: mask pixel counts ---
        val symbolsPx = masks.symbols.count { it }
        val stemsRestsPx = masks.stemsRests.count { it }
        val noteheadsPx = masks.noteheads.count { it }
        val clefsKeysPx = masks.clefsKeys.count { it }
        Log.d(TAG, "[OMR_GRID] masks: symbols=$symbolsPx stemsRests=$stemsRestsPx noteheads=$noteheadsPx clefsKeys=$clefsKeysPx")
        val barlineCandidateMask = buildBarlineCandidateMask(masks)
        val candidatePx = barlineCandidateMask.count { it }
        Log.d(TAG, "[OMR_GRID] barlineCandidateMask pixels=$candidatePx")
        // --- Primary detection: connected-component boxes from the candidate mask ---
        // This matches oemer's get_bbox(sep_pred): the barline residual's own
        // connected components are the primary barline candidates, not Hough
        // line rasterizations.
        val rawBoxes = ConnectedComponentBoxExtractor.extract(barlineCandidateMask, width, height)
        Log.d(TAG, "[OMR_GRID] rawCandidateBoxes=${rawBoxes.size}")
        // --- Secondary filter: Hough lines as validation ---
        val houghLines = HoughLineDetector.detect(barlineCandidateMask, width, height)
        val filteredLines = BarlineCandidateFilter.filterLines(houghLines, staffBounds)
        Log.d(TAG, "[OMR_GRID] houghLines=${houghLines.size} filteredLines=${filteredLines.size}")
        // Keep only boxes whose x-center overlaps a filtered Hough line's
        // x-range — oemer's secondary validation.  If no Hough lines
        // survived, pass all boxes through (graceful degradation).
        val barlineBoxes: List<BoundingBox>
        if (filteredLines.isNotEmpty()) {
            barlineBoxes = rawBoxes.filter { box ->
                val bxCenter = (box.left + box.right) / 2
                filteredLines.any { line ->
                    bxCenter in line.topX..line.btX
                }
            }
            Log.d(TAG, "[OMR_GRID] houghValidatedBoxes=${barlineBoxes.size} (from ${rawBoxes.size} raw)")
        } else {
            barlineBoxes = rawBoxes
            Log.d(TAG, "[OMR_GRID] noHoughLines, using all ${rawBoxes.size} rawBoxes")
        }
        // Build a mask from the validated boxes for TrackVotingLoop, which
        // expects a BooleanArray barline mask (it runs its own connected-
        // component extraction internally).
        val barlineMask = rasterizeBoxes(barlineBoxes, width, height)
        val trackVote = TrackVotingLoop.infer(barlineMask, width, height, staffCenterGrid)
        Log.d(TAG, "[OMR_GRID] barlineBoxes=${trackVote.barlineBoxes.size} votes=${trackVote.votes.size} trackNums=${trackVote.trackNums}")
        val assignedGrid = StaffTrackGroupAssigner.assign(zoneStaffGrid, trackVote.trackNums)
        val validatedGrid = StaffGridValidator.validate(assignedGrid)
        return ValidatedStaffGridResult(page = page, trackVote = trackVote, validatedGrid = validatedGrid)
    }
    private fun buildBarlineCandidateMask(masks: OmrClassMasks): BooleanArray =
        BooleanArray(masks.width * masks.height) { i ->
            val residual = (if (masks.symbols[i]) 1 else 0) -
                    (if (masks.stemsRests[i]) 1 else 0) -
                    (if (masks.noteheads[i]) 1 else 0) -
                    (if (masks.clefsKeys[i]) 1 else 0)
            residual > 0
        }
    /** Rasterizes [BoundingBox]es into a boolean mask. */
    private fun rasterizeBoxes(boxes: List<BoundingBox>, width: Int, height: Int): BooleanArray {
        val mask = BooleanArray(width * height)
        for (box in boxes) {
            val top = box.top.coerceIn(0, height - 1)
            val bottom = (box.bottom - 1).coerceIn(0, height - 1) // exclusive → inclusive
            val left = box.left.coerceIn(0, width - 1)
            val right = (box.right - 1).coerceIn(0, width - 1) // exclusive → inclusive
            for (y in top..bottom) {
                val rowBase = y * width
                for (x in left..right) mask[rowBase + x] = true
            }
        }
        return mask
    }
}
data class ValidatedStaffGridResult(
    val page: DewarpedPage,
    val trackVote: TrackVotingLoop.TrackVoteResult,
    val validatedGrid: List<List<AssignedStaff>>
)
