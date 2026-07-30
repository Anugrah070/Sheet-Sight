package com.sheetsight.app.data.omr.track

import com.sheetsight.app.data.omr.staffline.ZoneStaff

object StaffTrackGroupAssigner {

    fun assign(staffGrid: List<List<ZoneStaff>>, numTrack: Int): List<List<AssignedStaff>> {
        require(numTrack >= 1) { "numTrack must be >= 1, was $numTrack" }

        return staffGrid.map { zoneStaffs ->
            zoneStaffs.mapIndexed { index, staff ->
                AssignedStaff(
                    staff = staff,
                    track = index % numTrack,
                    group = index / numTrack
                )
            }
        }
    }
}

data class AssignedStaff(val staff: ZoneStaff, val track: Int, val group: Int)
