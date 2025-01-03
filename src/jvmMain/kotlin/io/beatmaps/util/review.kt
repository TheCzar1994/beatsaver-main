package io.beatmaps.util

import io.beatmaps.api.HydratedMapReportData
import io.beatmaps.api.HydratedPlaylistReportData
import io.beatmaps.api.HydratedReviewReportData
import io.beatmaps.api.HydratedUserReportData
import io.beatmaps.api.IssueCommentDetail
import io.beatmaps.api.IssueDetail
import io.beatmaps.api.ReviewDetail
import io.beatmaps.api.ReviewUpdateInfo
import io.beatmaps.api.UserDetail
import io.beatmaps.api.complexToReview
import io.beatmaps.api.from
import io.beatmaps.common.Config
import io.beatmaps.common.consumeAck
import io.beatmaps.common.db.avgWithFilter
import io.beatmaps.common.db.countWithFilter
import io.beatmaps.common.dbo.Beatmap
import io.beatmaps.common.dbo.Issue
import io.beatmaps.common.dbo.IssueComment
import io.beatmaps.common.dbo.Review
import io.beatmaps.common.dbo.User
import io.beatmaps.common.dbo.handleUser
import io.beatmaps.common.dbo.joinCurator
import io.beatmaps.common.dbo.joinUploader
import io.beatmaps.common.dbo.joinUser
import io.beatmaps.common.dbo.joinVersions
import io.beatmaps.common.dbo.reviewerAlias
import io.beatmaps.common.rabbitOptional
import io.beatmaps.common.util.TextHelper
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.userAgent
import io.ktor.server.application.Application
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ExpressionWithColumnType
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.decimalLiteral
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal

@Serializable
data class DiscordWebhookBody(
    val content: String? = null,
    val username: String? = null,
    val avatar_url: String? = null,
    val tts: Boolean? = false,
    val embeds: List<DiscordEmbed>? = null
)

@Serializable
data class DiscordEmbed(
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val color: Int? = null,
    val footer: Footer? = null,
    val thumbnail: HasUrl? = null,
    val image: HasUrl? = null,
    val author: Author? = null,
    val fields: List<Field>? = null
) {
    @Serializable
    data class Footer(
        val text: String? = null,
        @SerialName("icon_url")
        val iconUrl: String? = null
    )

    @Serializable
    data class HasUrl(
        val url: String? = null
    )

    @Serializable
    data class Author(
        val name: String? = null,
        val url: String? = null,
        @SerialName("icon_url")
        val iconUrl: String? = null
    ) {
        constructor(user: UserDetail) : this(user.name, user.profileLink(absolute = true), user.avatar)
    }

    @Serializable
    data class Field(
        val name: String? = null,
        val value: String? = null,
        val inline: Boolean? = null
    )
}

val discordWebhookUrl: String? = System.getenv("DISCORD_WEBHOOK_URL")
val discordIssueWebhookUrl: String? = System.getenv("DISCORD_ISSUE_WEBHOOK_URL")

class DiscordWebhookHandler(private val client: HttpClient, private val webhookUrl: String) {
    companion object {
        private const val MAX_TITLE_LEN = 100 // 256 max
        private const val MAX_REVIEW_LEN = 1024 // 1024 max
    }

    suspend fun post(review: ReviewDetail) {
        client.post(webhookUrl) {
            contentType(ContentType.Application.Json)
            userAgent("BeatSaver")

            setBody(
                DiscordWebhookBody(
                    username = "BeatSaver",
                    avatar_url = "https://avatars.githubusercontent.com/u/83342266",
                    embeds = listOf(
                        DiscordEmbed(
                            author = review.creator?.let { user -> DiscordEmbed.Author(user) },
                            title = review.map?.name?.let {
                                TextHelper.ellipsize(it, MAX_TITLE_LEN, true)
                            },
                            url = review.map?.let {
                                "${Config.siteBase()}/maps/${it.id}"
                            },
                            thumbnail = review.map?.mainVersion()?.let {
                                DiscordEmbed.HasUrl(
                                    "${Config.cdnBase("", true)}/${it.hash}.jpg"
                                )
                            },
                            fields = listOf(
                                DiscordEmbed.Field(
                                    "Review",
                                    TextHelper.ellipsize(review.text, MAX_REVIEW_LEN, true)
                                ),
                                DiscordEmbed.Field(
                                    "Sentiment",
                                    review.sentiment.let { s -> "${s.emoji} ${s.name}" },
                                    true
                                )
                            ),
                            color = 6973358
                        )
                    )
                )
            )
        }
    }

    suspend fun post(issue: IssueDetail, comment: IssueCommentDetail) {
        client.post(webhookUrl) {
            contentType(ContentType.Application.Json)
            userAgent("BeatSaver")

            setBody(
                DiscordWebhookBody(
                    username = "BeatSaver",
                    avatar_url = "https://avatars.githubusercontent.com/u/83342266",
                    embeds = listOf(
                        DiscordEmbed(
                            title = "New ${issue.type.human()} created",
                            description = comment.text,
                            url = "${Config.siteBase()}/issues/${issue.id}",
                            thumbnail = when (issue.data) {
                                is HydratedMapReportData -> issue.data.map.mainVersion()?.let {
                                    "${Config.cdnBase("", true)}/${it.hash}.jpg"
                                }
                                is HydratedUserReportData -> issue.data.user.avatar
                                is HydratedPlaylistReportData -> issue.data.playlist.playlistImage
                                is HydratedReviewReportData -> issue.data.review.map?.mainVersion()?.let {
                                    "${Config.cdnBase("", true)}/${it.hash}.jpg"
                                }
                                null -> "https://avatars.githubusercontent.com/u/83342266"
                            }?.let {
                                DiscordEmbed.HasUrl(it)
                            },
                            fields = when (issue.data) {
                                is HydratedMapReportData -> listOf(
                                    DiscordEmbed.Field(
                                        "Map",
                                        issue.data.map.let { "[${it.name}](${Config.siteBase()}/maps/${it.id})" }
                                    ),
                                    issue.data.map.uploader.let { u ->
                                        DiscordEmbed.Field(
                                            "Uploader",
                                            "[${u.name}](${u.profileLink(absolute = true)})"
                                        )
                                    }
                                )
                                is HydratedUserReportData -> listOf(
                                    DiscordEmbed.Field(
                                        "User",
                                        "[${issue.data.user.name}](${issue.data.user.profileLink(absolute = true)})"
                                    )
                                )
                                is HydratedPlaylistReportData -> listOf(
                                    DiscordEmbed.Field(
                                        "Playlist",
                                        "[${issue.data.playlist.name}](${issue.data.playlist.link(true)})"
                                    ),
                                    DiscordEmbed.Field(
                                        "Uploader",
                                        "[${issue.data.playlist.owner.name}](${issue.data.playlist.owner.profileLink(absolute = true)})"
                                    )
                                )
                                is HydratedReviewReportData -> listOfNotNull(
                                    issue.data.review.map?.let { m ->
                                        DiscordEmbed.Field(
                                            "Map",
                                            "[${m.name}](${m.link(true)})"
                                        )
                                    },
                                    issue.data.review.creator?.let { u ->
                                        DiscordEmbed.Field(
                                            "Reviewer",
                                            "[${u.name}](${u.profileLink(absolute = true)})"
                                        )
                                    }
                                )
                                null -> listOf()
                            },
                            color = 11431783
                        )
                    )
                )
            )
        }
    }
}

fun Application.reviewListeners(client: HttpClient) {
    rabbitOptional {
        val avg = Review.sentiment.avgWithFilter(Review.deletedAt.isNull(), 3).alias("sentiment")
        val count = Review.sentiment.countWithFilter(Review.deletedAt.isNull()).alias("reviews")
        val reviewSubquery = Review.select(avg, count, Review.mapId).groupBy(Review.mapId).alias("r")

        consumeAck("bm.sentiment", ReviewUpdateInfo::class) { _, r ->
            transaction {
                Beatmap
                    .join(reviewSubquery, JoinType.INNER, Beatmap.id, reviewSubquery[Review.mapId])
                    .update({ Beatmap.id eq r.mapId }) {
                        it[Beatmap.sentiment] = SqlExpressionBuilder.coalesce(reviewSubquery[avg] as ExpressionWithColumnType<BigDecimal?>, decimalLiteral(BigDecimal.ZERO))
                        it[Beatmap.reviews] = reviewSubquery[count]
                    }
            }
        }

        discordWebhookUrl?.let { webhookUrl ->
            val handler = DiscordWebhookHandler(client, webhookUrl)
            consumeAck("bm.reviewDiscordHook", ReviewUpdateInfo::class) { _, r ->
                transaction {
                    Review
                        .join(reviewerAlias, JoinType.INNER, Review.userId, reviewerAlias[User.id])
                        .join(Beatmap, JoinType.INNER, Review.mapId, Beatmap.id)
                        .joinVersions()
                        .joinUploader()
                        .joinCurator()
                        .selectAll()
                        .where {
                            Review.mapId eq r.mapId and (Review.userId eq r.userId)
                        }
                        .complexToReview()
                        .singleOrNull()?.let { row ->
                            ReviewDetail.from(row, "")
                        }
                }?.let { review ->
                    handler.post(review)
                }
            }
        }

        discordIssueWebhookUrl?.let { webhookUrl ->
            val handler = DiscordWebhookHandler(client, webhookUrl)
            consumeAck("bm.issuesDiscordHook", Int::class) { _, issueId ->
                transaction {
                    Issue
                        .join(IssueComment, JoinType.LEFT, Issue.id, IssueComment.issueId)
                        .joinUser(Issue.creator)
                        .selectAll()
                        .where {
                            (Issue.id eq issueId) and IssueComment.public
                        }
                        .orderBy(IssueComment.createdAt to SortOrder.ASC)
                        .limit(1)
                        .handleUser()
                        .singleOrNull()?.let { row ->
                            IssueDetail.from(row, "", false) to IssueCommentDetail.from(row)
                        }
                }?.let { (issue, comment) ->
                    handler.post(issue, comment)
                }
            }
        }
    }
}
