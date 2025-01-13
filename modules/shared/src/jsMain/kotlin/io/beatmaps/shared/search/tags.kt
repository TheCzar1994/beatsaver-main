package io.beatmaps.shared.search

import io.beatmaps.common.MapTag
import io.beatmaps.common.MapTagSet
import io.beatmaps.common.MapTagType
import io.beatmaps.maps.mapTag
import io.beatmaps.util.applyIf
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import react.Props
import react.dom.div
import react.dom.h4
import react.fc
import react.useEffect
import react.useEffectOnce
import react.useState

external interface TagsProps : Props {
    var default: MapTagSet?
    var callback: ((MapTagSet) -> Unit)?
    var highlightOnEmpty: Boolean?
}

val tags = fc<TagsProps>("tags") { props ->
    val (selected, setSelected) = useState<MapTagSet>(emptyMap())
    val (altHeld, setAltHeld) = useState(false)
    val (shiftHeld, setShiftHeld) = useState(false)

    val handleShift = { it: Event ->
        val ke = (it as? KeyboardEvent)
        if (ke?.repeat == false) {
            setShiftHeld(ke.shiftKey)
            setAltHeld(ke.altKey)
        }
    }

    useEffect(props.default) {
        props.default?.let { setSelected(it) }
    }

    useEffectOnce {
        document.addEventListener("keyup", handleShift)
        document.addEventListener("keydown", handleShift)
        cleanup {
            document.removeEventListener("keyup", handleShift)
            document.removeEventListener("keydown", handleShift)
        }
    }

    div("tags") {
        h4 {
            +"Tags"
        }

        val highlightAll = props.highlightOnEmpty == true && selected.all { it.value.isEmpty() }
        MapTag.sorted.fold(MapTagType.None) { prev, it ->
            if (it.type != prev) div("break") {}

            if (it.type != MapTagType.None) {
                mapTag {
                    attrs.selected = selected.any { x -> x.value.contains(it) } || highlightAll
                    attrs.excluded = selected[false]?.contains(it) == true
                    attrs.tag = it

                    attrs.onClick = { _ ->
                        val t = selected[!altHeld] ?: setOf()

                        val shouldAdd = !t.contains(it)

                        val newTags = t.applyIf(!shiftHeld) {
                            filterTo(hashSetOf()) { o -> o.type != it.type }
                        }.applyIf(shouldAdd) {
                            plus(it)
                        }.applyIf(shiftHeld && !shouldAdd) {
                            minus(it)
                        }

                        val newSelected = mapOf(
                            !altHeld to newTags,
                            altHeld to (selected[altHeld]?.let { x -> x - it } ?: setOf())
                        )

                        setSelected(newSelected)
                        props.callback?.invoke(newSelected)
                        window.asDynamic().getSelection().removeAllRanges()
                        Unit
                    }
                }
            }
            it.type
        }
    }
}
