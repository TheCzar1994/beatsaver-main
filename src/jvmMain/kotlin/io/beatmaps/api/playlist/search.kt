package io.beatmaps.api.playlist

import de.nielsfalk.ktor.swagger.ok
import de.nielsfalk.ktor.swagger.responds
import io.beatmaps.api.LatestPlaylistSort
import io.beatmaps.api.OauthScope
import io.beatmaps.api.PlaylistApi
import io.beatmaps.api.PlaylistBasic
import io.beatmaps.api.PlaylistFull
import io.beatmaps.api.PlaylistSearchResponse
import io.beatmaps.api.from
import io.beatmaps.api.limit
import io.beatmaps.api.notNull
import io.beatmaps.api.search.PgSearchParams
import io.beatmaps.api.search.SolrSearchParams
import io.beatmaps.api.util.getWithOptions
import io.beatmaps.common.SearchOrder
import io.beatmaps.common.api.EPlaylistType
import io.beatmaps.common.db.PgConcat
import io.beatmaps.common.db.greaterEqF
import io.beatmaps.common.db.lessEqF
import io.beatmaps.common.dbo.Playlist
import io.beatmaps.common.dbo.PlaylistMap
import io.beatmaps.common.dbo.User
import io.beatmaps.common.dbo.curatorAlias
import io.beatmaps.common.dbo.handleCurator
import io.beatmaps.common.dbo.handleOwner
import io.beatmaps.common.dbo.joinOwner
import io.beatmaps.common.dbo.joinPlaylistCurator
import io.beatmaps.common.solr.collections.PlaylistSolr
import io.beatmaps.common.solr.field.ComposableSolrFilter
import io.beatmaps.common.solr.field.apply
import io.beatmaps.common.solr.getIds
import io.beatmaps.common.solr.paged
import io.beatmaps.util.cdnPrefix
import io.beatmaps.util.optionalAuthorization
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.datetime.toJavaInstant
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

fun Route.playlistSearch() {
    getWithOptions<PlaylistApi.ByUploadDate>("Get playlists ordered by created/updated".responds(ok<PlaylistSearchResponse>())) {
        optionalAuthorization(OauthScope.PLAYLISTS) { _, sess ->
            val sortField = when (it.sort) {
                null, LatestPlaylistSort.CREATED -> Playlist.createdAt
                LatestPlaylistSort.SONGS_UPDATED -> Playlist.songsChangedAt
                LatestPlaylistSort.UPDATED -> Playlist.updatedAt
                LatestPlaylistSort.CURATED -> Playlist.curatedAt
            }

            val playlists = transaction {
                Playlist
                    .joinMaps()
                    .joinPlaylistCurator()
                    .joinOwner()
                    .select(Playlist.columns + User.columns + curatorAlias.columns + Playlist.Stats.all)
                    .where {
                        Playlist.id.inSubQuery(
                            Playlist
                                .select(Playlist.id)
                                .where {
                                    (Playlist.deletedAt.isNull() and (sess?.let { s -> Playlist.owner eq s.userId or (Playlist.type eq EPlaylistType.Public) } ?: (Playlist.type eq EPlaylistType.Public)))
                                        .notNull(it.before) { o -> sortField less o.toJavaInstant() }
                                        .notNull(it.after) { o -> sortField greater o.toJavaInstant() }
                                        .let { q ->
                                            if (it.sort == LatestPlaylistSort.CURATED) q.and(Playlist.curatedAt.isNotNull()) else q
                                        }
                                }
                                .orderBy(sortField to (if (it.after != null) SortOrder.ASC else SortOrder.DESC))
                                .limit(it.pageSize.coerceIn(1, 100))
                        )
                    }
                    .groupBy(Playlist.id, User.id, curatorAlias[User.id])
                    .handleOwner()
                    .handleCurator()
                    .sortedByDescending { row -> row[sortField] }
                    .map { playlist ->
                        PlaylistFull.from(playlist, cdnPrefix())
                    }
            }

            call.respond(PlaylistSearchResponse(playlists))
        }
    }

    getWithOptions<PlaylistApi.Solr>("Search for playlists with solr".responds(ok<PlaylistSearchResponse>())) { req ->
        val searchInfo = SolrSearchParams.parseSearchQuery(req.q)
        val actualSortOrder = searchInfo.validateSearchOrder(req.sortOrder)

        newSuspendedTransaction {
            val results = PlaylistSolr.newQuery()
                .let { q ->
                    searchInfo.applyQuery(q)
                }
                .also { q ->
                    EPlaylistType.publicTypes
                        .map { PlaylistSolr.type eq it.name }
                        .reduce<ComposableSolrFilter, ComposableSolrFilter> { acc, f -> acc or f }
                        .let { q.apply(it) }
                }
                .also { q ->
                    if (req.includeEmpty != true) {
                        q.apply((PlaylistSolr.totalMaps greater 0) or (PlaylistSolr.type eq EPlaylistType.Search.name))
                    }
                }
                .also { q ->
                    val mapperIds = searchInfo.userSubQuery?.map { it[User.id].value } ?: listOf()

                    mapperIds.map { id ->
                        PlaylistSolr.ownerId eq id
                    }.reduceOrNull<ComposableSolrFilter, ComposableSolrFilter> { a, b -> a or b }?.let {
                        q.apply(it)
                    }
                }
                .notNull(req.minNps) { o -> PlaylistSolr.maxNps greaterEq o }
                .notNull(req.maxNps) { o -> PlaylistSolr.minNps lessEq o }
                .notNull(req.from) { o -> PlaylistSolr.created greaterEq o }
                .notNull(req.to) { o -> PlaylistSolr.created lessEq o }
                .notNull(req.curated) { o -> PlaylistSolr.curated.any().let { if (o) it else it.not() } }
                .notNull(req.verified) { o -> PlaylistSolr.verified eq o }
                .let { q ->
                    PlaylistSolr.addSortArgs(q, req.seed.hashCode(), actualSortOrder)
                }
                .paged(req.page.toInt())
                .getIds(PlaylistSolr, call = call)

            val playlists = Playlist
                .joinMaps()
                .joinOwner()
                .joinPlaylistCurator()
                .select(
                    Playlist.columns + User.columns + curatorAlias.columns + Playlist.Stats.all
                )
                .where {
                    Playlist.id inList results.mapIds
                }
                .groupBy(Playlist.id, User.id, curatorAlias[User.id])
                .handleCurator()
                .handleOwner()
                .map { playlist ->
                    PlaylistFull.from(playlist, cdnPrefix())
                }
                .sortedBy { results.order[it.playlistId] }

            call.respond(PlaylistSearchResponse(playlists, results.searchInfo))
        }
    }

    getWithOptions<PlaylistApi.Text>("Search for playlists".responds(ok<PlaylistSearchResponse>())) {
        val searchFields = PgConcat(" ", Playlist.name, Playlist.description)
        val searchInfo = PgSearchParams.parseSearchQuery(it.q, searchFields)
        val actualSortOrder = searchInfo.validateSearchOrder(it.sortOrder)
        val sortArgs = when (actualSortOrder) {
            SearchOrder.Curated -> listOf(Playlist.curatedAt to SortOrder.DESC_NULLS_LAST, Playlist.createdAt to SortOrder.DESC)
            else -> listOf(Playlist.createdAt to SortOrder.DESC)
        }.toTypedArray()

        newSuspendedTransaction {
            val playlists = Playlist
                .joinMaps()
                .joinOwner()
                .joinPlaylistCurator()
                .select(
                    Playlist.columns + User.columns + curatorAlias.columns + Playlist.Stats.all
                )
                .where {
                    Playlist.id.inSubQuery(
                        Playlist
                            .joinOwner()
                            .select(Playlist.id)
                            .where {
                                (Playlist.deletedAt.isNull() and (Playlist.type inList EPlaylistType.publicTypes))
                                    .let { q -> searchInfo.applyQuery(q) }
                                    .let { q ->
                                        if (it.includeEmpty != true) {
                                            q.and((Playlist.totalMaps greater 0) or (Playlist.type eq EPlaylistType.Search))
                                        } else { q }
                                    }
                                    .notNull(searchInfo.userSubQuery) { o -> Playlist.owner inSubQuery o }
                                    .notNull(it.minNps) { o -> Playlist.maxNps greaterEqF o }
                                    .notNull(it.maxNps) { o -> Playlist.minNps lessEqF o }
                                    .notNull(it.from) { o -> Playlist.createdAt greaterEq o.toJavaInstant() }
                                    .notNull(it.to) { o -> Playlist.createdAt lessEq o.toJavaInstant() }
                                    .notNull(it.curated) { o -> with(Playlist.curatedAt) { if (o) isNotNull() else isNull() } }
                                    .notNull(it.verified) { o -> User.verifiedMapper eq o }
                            }
                            .orderBy(*sortArgs)
                            .limit(it.page)
                    )
                }
                .groupBy(Playlist.id, User.id, curatorAlias[User.id])
                .orderBy(*sortArgs)
                .handleCurator()
                .handleOwner()
                .map { playlist ->
                    PlaylistFull.from(playlist, cdnPrefix())
                }

            call.respond(PlaylistSearchResponse(playlists))
        }
    }

    getWithOptions<PlaylistApi.ByUser>("Get playlists by user".responds(ok<PlaylistSearchResponse>())) { req ->
        optionalAuthorization(OauthScope.PLAYLISTS) { _, sess ->
            fun <T> doQuery(table: Query = Playlist.selectAll(), groupBy: Array<Column<*>> = arrayOf(Playlist.id), block: (ResultRow) -> T) =
                transaction {
                    table
                        .where {
                            Playlist.id.inSubQuery(
                                Playlist
                                    .select(Playlist.id)
                                    .where {
                                        ((Playlist.owner eq req.userId) and Playlist.deletedAt.isNull()).let {
                                            if (req.userId == sess?.userId) {
                                                it
                                            } else {
                                                it and (Playlist.type inList EPlaylistType.publicTypes)
                                            }
                                        }
                                    }
                                    .orderBy(
                                        (Playlist.type neq EPlaylistType.System) to SortOrder.ASC,
                                        Playlist.createdAt to SortOrder.DESC
                                    )
                                    .limit(req.page, 20)
                            )
                        }
                        .orderBy(
                            (Playlist.type neq EPlaylistType.System) to SortOrder.ASC,
                            Playlist.createdAt to SortOrder.DESC
                        )
                        .groupBy(*groupBy)
                        .handleOwner()
                        .handleCurator()
                        .map(block)
                }

            if (req.basic) {
                val page = doQuery {
                    PlaylistBasic.from(it, cdnPrefix())
                }

                call.respond(page)
            } else {
                val page = doQuery(
                    Playlist
                        .joinMaps()
                        .joinOwner()
                        .joinPlaylistCurator()
                        .select(Playlist.columns + User.columns + curatorAlias.columns + Playlist.Stats.all),
                    arrayOf(Playlist.id, User.id, curatorAlias[User.id])
                ) {
                    PlaylistFull.from(it, cdnPrefix())
                }

                call.respond(PlaylistSearchResponse(page))
            }
        }
    }

    getWithOptions<PlaylistApi.ByMap>("Get playlists by map".responds(ok<PlaylistSearchResponse>())) { req ->
        optionalAuthorization(OauthScope.PLAYLISTS) { _, sess ->
            fun <T> doQuery(table: Query = Playlist.selectAll(), groupBy: Array<Column<*>> = arrayOf(Playlist.id), block: (ResultRow) -> T) =
                transaction {
                    table
                        .where {
                            Playlist.id.inSubQuery(
                                Playlist
                                    .join(PlaylistMap, JoinType.INNER, Playlist.id, PlaylistMap.playlistId)
                                    .select(PlaylistMap.playlistId)
                                    .where {
                                        (PlaylistMap.mapId eq req.mapId.toInt(16) and Playlist.deletedAt.isNull()) and
                                            ((Playlist.type inList EPlaylistType.publicTypes) or (Playlist.owner eq sess?.userId))
                                                .notNull(req.curated) { o -> Playlist.curator.run { if (o) isNotNull() else isNull() } }
                                    }
                                    .orderBy(
                                        Playlist.curatedAt to SortOrder.DESC_NULLS_LAST,
                                        Playlist.createdAt to SortOrder.DESC
                                    )
                                    .limit(req.page, 20)
                            )
                        }
                        .orderBy(
                            Playlist.curatedAt to SortOrder.DESC_NULLS_LAST,
                            Playlist.createdAt to SortOrder.DESC
                        )
                        .groupBy(*groupBy)
                        .handleOwner()
                        .handleCurator()
                        .map(block)
                }

            if (req.basic) {
                val page = doQuery {
                    PlaylistBasic.from(it, cdnPrefix())
                }

                call.respond(page)
            } else {
                val page = doQuery(
                    Playlist
                        .joinMaps()
                        .joinOwner()
                        .joinPlaylistCurator()
                        .select(Playlist.columns + User.columns + curatorAlias.columns + Playlist.Stats.all),
                    arrayOf(Playlist.id, User.id, curatorAlias[User.id])
                ) {
                    PlaylistFull.from(it, cdnPrefix())
                }

                call.respond(PlaylistSearchResponse(page))
            }
        }
    }
}
