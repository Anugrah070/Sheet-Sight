package com.sheetsight.app.data.omr.track

import android.util.Log
import com.sheetsight.app.data.omr.dewarp.DewarpedPage
import com.sheetsight.app.data.omr.staffline.ZoneStaff

/**
 * Converts dewarped masks into oemer's aligned staff grid and infers the
 * number of tracks from barline geometry.
 */
object OmrStaffGridAssembler {
    private const val TAG = "OmrGrid"

    fun assemble(page: DewarpedPage): ValidatedStaffGridResult {
        val masks = page.masks
        val width = page.width
        val height = page.height

        // oemer extracts eight horizontal zones. The old full-page call
        // guaranteed `validatedZones=1` regardless of the input.
        val zoneStaffGrid = StaffZoneGridExtractor.extract(masks.staff, width, height)
        val staffRows = zoneStaffGrid.maxOfOrNull { it.size } ?: 0
        Log.d(
            TAG,
            "[OMR_GRID] zones=${zoneStaffGrid.size} staffRows=$staffRows page=${width}x$height"
        )
        if (zoneStaffGrid.isEmpty() || staffRows == 0) {
            return ValidatedStaffGridResult(
                page = page,
                trackVote = TrackVotingLoop.infer(
                    BooleanArray(width * height),
                    width,
                    height,
                    emptyList()
                ),
                validatedGrid = emptyList()
            )
        }

        val staffCenterGrid = zoneStaffGrid.toCenterGrid()
        val staffBounds = zoneStaffGrid.flatten().map { staff ->
            StaffBounds(
                yUpper = staff.lines.minOf { it.yUpper },
                yLower = staff.lines.maxOf { it.yLower },
                xLeft = staff.lines.minOf { it.xLeft },
                xRight = staff.lines.maxOf { it.xRight }
            )
        }

        // The residual is only the Hough input. It is not itself the
        // barline mask and must never be passed wholesale to CC extraction.
        val houghInput = BarlineMaskBuilder.houghInput(masks)
        val houghLines = HoughLineDetector.detect(houghInput, width, height)
        val acceptedLines = BarlineCandidateFilter.filterLines(houghLines, staffBounds)

        // Source flow: selected generic-symbol pixels + all predicted
        // stems/straight lines, followed by a 5x2 morphological closing.
        val barlineMask = BarlineMaskBuilder.build(masks, acceptedLines)
        val trackVote = TrackVotingLoop.infer(
            barlineMask,
            width,
            height,
            staffCenterGrid
        )

        Log.d(
            TAG,
            "[OMR_GRID] hough=${houghLines.size} accepted=${acceptedLines.size} " +
                "barlineBoxes=${trackVote.barlineBoxes.size} " +
                "heightRatios=${trackVote.heightRatios.size} trackNums=${trackVote.trackNums}"
        )

        val assignedGrid = StaffTrackGroupAssigner.assign(zoneStaffGrid, trackVote.trackNums)
        val validatedGrid = StaffGridValidator.validate(assignedGrid)
        return ValidatedStaffGridResult(page, trackVote, validatedGrid)
    }

    private fun List<List<ZoneStaff>>.toCenterGrid(): List<List<StaffCenterInfo>> =
        map { zone ->
            zone.map { staff ->
                StaffCenterInfo(
                    xCenter = staff.lines.sumOf { it.xCenter } / staff.lines.size,
                    yCenter = staff.yCenter,
                    unitSize = staff.unitSize
                )
            }
        }
}

data class ValidatedStaffGridResult(
    val page: DewarpedPage,
    val trackVote: TrackVotingLoop.TrackVoteResult,
    val validatedGrid: List<List<AssignedStaff>>
)
