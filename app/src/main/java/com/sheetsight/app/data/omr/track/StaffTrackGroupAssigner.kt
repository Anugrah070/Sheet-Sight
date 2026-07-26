package com.sheetsight.app.data.omr.track

import com.sheetsight.app.data.omr.staffline.ZoneStaff

object StaffTrackGroupAssigner {

    fun assign(staffGrid: List<List<ZoneStaff>>, numTrack: Int): List<List<AssignedStaff>> {
        require(numTrack >= 1) { "numTrack must be >= 1, was $numTrack" }

        var idx = 0
        return staffGrid.map { zoneStaffs ->
            zoneStaffs.map { staff ->
                val assigned = AssignedStaff(
                    staff = staff,
                    track = idx % numTrack,
                    group = idx / numTrack
                )
                idx++
                assigned
            }
        }
    }
}

data class AssignedStaff(val staff: ZoneStaff, val track: Int, val group: Int)