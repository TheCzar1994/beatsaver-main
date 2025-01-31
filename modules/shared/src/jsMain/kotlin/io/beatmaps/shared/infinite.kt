package io.beatmaps.shared

import external.Axios
import external.CancelTokenSource
import external.invoke
import io.beatmaps.api.GenericSearchResponse
import io.beatmaps.util.fcmemo
import js.array.asList
import react.ChildrenBuilder
import react.Props
import react.RefObject
import react.useEffect
import react.useEffectOnceWithCleanup
import react.useEffectWithCleanup
import react.useRef
import react.useState
import web.dom.Element
import web.events.Event
import web.events.addEventListener
import web.events.removeEventListener
import web.history.HashChangeEvent
import web.html.HTMLElement
import web.timers.setTimeout
import web.window.window
import kotlin.js.Promise
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.reflect.KClass

sealed interface ElementRenderer<T>

fun interface InfiniteScrollElementRenderer<T> : ElementRenderer<T> {
    fun ChildrenBuilder.invoke(it: T?)
}

fun interface IndexedInfiniteScrollElementRenderer<T> : ElementRenderer<T> {
    fun ChildrenBuilder.invoke(idx: Int, it: T?)
}

external interface InfiniteScrollProps<T> : Props {
    var resetRef: RefObject<() -> Unit>
    var rowHeight: Double
    var itemsPerRow: RefObject<() -> Int>?
    var itemsPerPage: Int
    var container: RefObject<HTMLElement>
    var renderElement: ElementRenderer<T>
    var updateScrollIndex: RefObject<(Int) -> Unit>?
    var loadPage: RefObject<(Int, CancelTokenSource) -> Promise<GenericSearchResponse<T>?>>?
    var grace: Int?
    var childFilter: ((Element) -> Boolean)?
    var scrollParent: Element?
    var headerSize: Double?
}

fun <T : Any> generateInfiniteScrollComponent(clazz: KClass<T>) = generateInfiniteScrollComponentInternal<T>(clazz.simpleName ?: "Unknown")

private fun <T> generateInfiniteScrollComponentInternal(name: String) = fcmemo<InfiniteScrollProps<T>>("${name}InfiniteScroll") { props ->
    val (pages, setPages) = useState(emptyMap<Int, List<T>>())

    val loading = useRef(false)
    val pagesRef = useRef<Map<Int, List<T>>>()

    val finalPage = useRef<Int>()
    val itemsPerRow = useRef<Int>()

    val visItem = useRef<Int>()
    val visPage = useRef<Int>()
    val visiblePages = useRef<IntRange>()
    val scroll = useRef<Int>()
    val token = useRef<CancelTokenSource>()
    val location = useRef(window.location.search)
    val pathname = window.location.pathname

    val itemsPerPage = useRef(props.itemsPerPage)
    val loadNextPage = useRef<() -> Unit>()

    val emptyPage = List<T?>(props.itemsPerPage) { null }

    fun rowsPerPage() = (itemsPerPage.current ?: 20) / (itemsPerRow.current ?: 1)
    fun pageHeight() = props.rowHeight * rowsPerPage()
    fun headerSize() = props.headerSize ?: 54.5
    fun beforeContent() = headerSize() + (props.grace ?: 5)

    fun lastPage() = min(
        finalPage.current ?: Int.MAX_VALUE,
        (pages.maxByOrNull { it.key }?.key ?: 0).let { alt ->
            visiblePages.current?.let {
                max(it.last, alt)
            } ?: alt
        }
    )

    fun filteredChildren() = props.container.current?.children?.asList()?.filter(props.childFilter ?: { true })

    fun currentItem(): Int {
        filteredChildren()?.forEachIndexed { idx, it ->
            val rect = it.getBoundingClientRect()
            if (rect.top >= headerSize()) {
                return idx
            }
        }
        return 0
    }

    fun scrollTo(x: Double, y: Double) {
        props.scrollParent?.scrollTo(x, y) ?: run {
            window.scrollTo(x, y)
        }
    }

    fun scrollTo(idx: Int): Boolean {
        val (scrollTo: Double, found: Boolean) = if (idx == 0) { 0.0 to true } else {
            val rect = filteredChildren()?.get(idx)?.getBoundingClientRect()
            val top = rect?.top ?: 0.0
            val offset = props.scrollParent?.scrollTop ?: window.pageYOffset
            top + offset - beforeContent() to (rect != null)
        }
        scrollTo(0.0, scrollTo)
        return found
    }

    fun innerHeight() = props.scrollParent?.clientHeight ?: window.innerHeight

    fun updateState(newItem: Int = currentItem()): Int {
        val totalVisiblePages = ceil(innerHeight() / pageHeight()).toInt()
        val newPage = max(1, newItem - (itemsPerRow.current ?: 1)) / (itemsPerPage.current ?: 20)

        visItem.current = newItem
        visPage.current = newPage
        visiblePages.current = newPage.rangeTo(newPage + totalVisiblePages)

        return newPage
    }

    val onResize = { _: Event ->
        val newItemsPerRow = props.itemsPerRow?.current?.invoke() ?: 1
        if (itemsPerRow.current != newItemsPerRow) {
            visItem.current?.let { scrollTo(it) }
            itemsPerRow.current = newItemsPerRow
        }
    }

    val onScroll: (Event?) -> Unit = { _: Event? ->
        // Don't run while transitioning to another page
        if (pathname == window.location.pathname) {
            val item = currentItem()

            if (item != visItem.current && location.current == window.location.search && scroll.current == null) {
                updateState(item)
                props.updateScrollIndex?.current?.invoke(item + 1)
            }

            loadNextPage.current?.invoke()
        }
    }

    fun setPagesAndRef(newPages: Map<Int, List<T>>? = null) {
        setPages(newPages ?: LinkedHashMap())
        pagesRef.current = newPages
    }

    val onHashChange: (Event?) -> Unit = { _: Event? ->
        val hashPos = window.location.hash.substring(1).toIntOrNull()

        val oldItem = visItem.current
        val newItem = (hashPos ?: 1) - 1
        val newPage = updateState(newItem)

        scroll.current = if (hashPos != null) newItem else null

        if (newItem == 0) {
            scrollTo(0.0, 0.0)
        } else if (pagesRef.current?.containsKey(newPage) == true) {
            scrollTo(newItem)
        } else if (oldItem != newItem) {
            // Trigger re-render
            setPagesAndRef(pagesRef.current?.toMap())
        }
    }

    loadNextPage.current = fun() {
        if (loading.current == true) return

        // Find first visible page that isn't loaded or beyond the final page
        val toLoad = visiblePages.current?.firstOrNull { finalPage.current?.let { f -> it < f } != false && pagesRef.current?.containsKey(it) != true } ?: return

        val newToken = token.current ?: Axios.CancelToken.source()
        token.current = newToken
        loading.current = true

        props.loadPage?.current!!.invoke(toLoad, newToken).then { page ->
            val lastPage = page?.info?.pages?.let { (toLoad + 1) >= it } ?: // Loaded page (ie 0) is beyond the number of pages that exist (ie 1)
                (page?.docs?.size?.let { it < (itemsPerPage.current ?: 20) } == true) // Or there aren't the expected number of results which should only happen on the last page

            if (lastPage && toLoad < (finalPage.current ?: Int.MAX_VALUE)) {
                finalPage.current = toLoad
            }

            setPagesAndRef(
                page?.docs?.let { docs ->
                    (pagesRef.current ?: emptyMap()).plus(toLoad to docs)
                } ?: pagesRef.current
            )

            loading.current = false

            setTimeout({
                loadNextPage.current?.invoke()
            }, 1)
        }.catch {
            loading.current = false
        }
    }

    // Run as part of first render, useEffect happens after the render
    if (token.current == null) {
        onHashChange(null)
        token.current = Axios.CancelToken.source()
        loadNextPage.current?.invoke()
    }

    useEffectOnceWithCleanup {
        onCleanup {
            token.current?.cancel("Unmounted")
        }
    }

    useEffectWithCleanup {
        if (scroll.current?.let { scrollTo(it) } == true) {
            scroll.current = null
        }
        location.current = window.location.search

        window.addEventListener(Event.RESIZE, onResize)
        window.addEventListener(HashChangeEvent.HASH_CHANGE, onHashChange)
        onCleanup {
            window.removeEventListener(Event.RESIZE, onResize)
            window.removeEventListener(HashChangeEvent.HASH_CHANGE, onHashChange)
        }
    }

    useEffectWithCleanup(props.scrollParent) {
        val target = props.scrollParent ?: window

        target.addEventListener(Event.SCROLL, onScroll)
        onCleanup {
            target.removeEventListener(Event.SCROLL, onScroll)
        }
    }

    useEffect(visItem.current) {
        loadNextPage.current?.invoke()
    }

    props.resetRef.current = {
        token.current?.cancel("Another request started")

        updateState(0)
        scroll.current = null
        loading.current = false
        setPagesAndRef()
        token.current = null
        finalPage.current = null
    }

    for (pIdx in 0..lastPage()) {
        (pages[pIdx] ?: emptyPage).forEachIndexed userLoop@{ localIdx, it ->
            val idx = (pIdx * props.itemsPerPage) + localIdx
            with(props.renderElement) {
                when (this) {
                    is InfiniteScrollElementRenderer -> this@fcmemo.invoke(it)
                    is IndexedInfiniteScrollElementRenderer -> this@fcmemo.invoke(idx, it)
                }
            }
        }
    }
}
