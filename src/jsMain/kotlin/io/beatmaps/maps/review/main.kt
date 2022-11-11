package io.beatmaps.maps.review

import external.Axios
import external.CancelTokenSource
import external.generateConfig
import io.beatmaps.api.ReviewDetail
import io.beatmaps.api.ReviewsResponse
import io.beatmaps.common.Config
import io.beatmaps.globalContext
import io.beatmaps.index.ModalComponent
import io.beatmaps.shared.InfiniteScroll
import io.beatmaps.shared.InfiniteScrollElementRenderer
import org.w3c.dom.HTMLDivElement
import react.RBuilder
import react.RComponent
import react.RProps
import react.RReadableRef
import react.RState
import react.ReactElement
import react.createRef
import react.dom.div
import react.setState

external interface ReviewTableProps : RProps {
    var map: String
    var modal: RReadableRef<ModalComponent>
}

external interface ReviewTableState : RState {
    var resultsKey: Any
    var existingReview: Boolean?
}

class ReviewTable : RComponent<ReviewTableProps, ReviewTableState>() {
    private val resultsTable = createRef<HTMLDivElement>()

    override fun componentWillUpdate(nextProps: ReviewTableProps, nextState: ReviewTableState) {
        if (props.map != nextProps.map) {
            nextState.apply {
                resultsKey = Any()
            }
        }
    }

    private fun getUrl(page: Int) = "${Config.apibase}/review/map/${props.map}/$page"

    private val loadPage = { toLoad: Int, token: CancelTokenSource ->
        Axios.get<ReviewsResponse>(
            getUrl(toLoad),
            generateConfig<String, ReviewsResponse>(token.token)
        ).then {
            return@then it.data.docs
        }
    }

    override fun RBuilder.render() {
        div("reviews col-lg-8") {
            ref = resultsTable
            key = "resultsTable"

            globalContext.Consumer { userData ->
                if (userData != null) {
                    newReview {
                        mapId = props.map
                        userId = userData.userId
                        existingReview = state.existingReview
                        setExistingReview = { nv ->
                            setState {
                                existingReview = nv
                            }
                        }
                        reloadList = {
                            setState {
                                resultsKey = Any()
                            }
                        }
                    }
                }
            }

            child(CommentsInfiniteScroll::class) {
                attrs.resultsKey = state.resultsKey
                attrs.rowHeight = 116.0
                attrs.itemsPerPage = 20
                attrs.container = resultsTable
                attrs.renderElement = InfiniteScrollElementRenderer { rv ->
                    reviewItem {
                        obj = rv
                        mapId = props.map
                        userId = rv?.creator?.id ?: -1
                        modal = props.modal
                        setExistingReview = { nv ->
                            setState {
                                existingReview = nv
                            }
                        }
                    }
                }
                attrs.loadPage = loadPage
            }
        }
    }
}

class CommentsInfiniteScroll : InfiniteScroll<ReviewDetail>()

fun RBuilder.reviewTable(handler: ReviewTableProps.() -> Unit): ReactElement {
    return child(ReviewTable::class) {
        this.attrs(handler)
    }
}