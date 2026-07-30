package com.sheetsight.app.data.omr.track

import com.sheetsight.app.data.omr.dewarp.DewarpedPage
import com.sheetsight.app.data.omr.inference.OmrClassMasks
import com.sheetsight.app.data.omr.staffline.ZoneStaff
import com.sheetsight.app.data.omr.staffline.ZoneStafflineExtractor

/**
 * Orchestrates the transition from dewarped pixel masks to a structured,
 * validated staff grid. Combines per-zone staffline extraction, barline
 * detection, track voting, and final grid validation.
 */
object OmrStaffGridAssembler {

    fun assemble(page: DewarpedPage): ValidatedStaffGridResult {
        val masks = page.masks
        val width = page.width
        val height = page.height

        val zoneResult = ZoneStafflineExtractor.extract(masks.staff, width, height, zoneLeft = 0, zoneRight = width)
        val zoneStaffGrid: List<List<ZoneStaff>> = listOf(zoneResult.staffs)

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

        val barlineCandidateMask = buildBarlineCandidateMask(masks)
        val houghLines = HoughLineDetector.detect(barlineCandidateMask, width, height)
        val filteredLines = BarlineCandidateFilter.filterLines(houghLines, staffBounds)
        val barlineMask = rasterizeAsBoxes(filteredLines, width, height)

        val trackVote = TrackVotingLoop.infer(barlineMask, width, height, staffCenterGrid)
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

    private fun rasterizeAsBoxes(lines: List<HoughLine>, width: Int, height: Int): BooleanArray {
        val mask = BooleanArray(width * height)
        for (line in lines) {
            val top = line.topY.coerceIn(0, height - 1)
            val bottom = line.btY.coerceIn(0, height - 1)
            val left = line.topX.coerceIn(0, width - 1)
            val right = line.btX.coerceIn(0, width - 1)
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
