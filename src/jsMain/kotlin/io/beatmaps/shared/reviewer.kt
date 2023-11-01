package io.beatmaps.shared

import external.TimeAgo
import external.routeLink
import io.beatmaps.api.MapDetail
import io.beatmaps.api.UserDetail
import kotlinx.datetime.Instant
import react.Props
import react.fc

external interface ReviewerProps : Props {
    var reviewer: UserDetail?
    var map: MapDetail?
    var time: Instant
}

val reviewer = fc<ReviewerProps> { props ->
    props.reviewer?.let { owner ->
        routeLink(owner.profileLink("reviews")) {
            +owner.name
        }
        +" - "
    }
    props.map?.let { map ->
        +" on "
        routeLink("/maps/${map.id}") {
            +map.name
        }
        +" - "
    }
    TimeAgo.default {
        attrs.date = props.time.toString()
    }
}
