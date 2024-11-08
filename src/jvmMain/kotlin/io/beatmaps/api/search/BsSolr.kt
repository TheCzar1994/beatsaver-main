package io.beatmaps.api.search

object BsSolr : SolrCollection() {
    val author = string("author")
    val created = pdate("created")
    val deleted = pdate("deleted")
    val description = string("description")
    val id = pint("id")
    val mapId = string("mapId")
    val mapper = string("mapper")
    val mapperIds = pints("mapperIds")
    val name = string("name")
    val updated = pdate("updated")
    val curated = pdate("curated")
    val uploaded = pdate("uploaded")
    val voteScore = pfloat("voteScore")
    val verified = boolean("verified")
    val rankedss = boolean("rankedss")
    val rankedbl = boolean("rankedbl")
    val ai = boolean("ai")
    val mapperId = pint("mapperId")
    val curatorId = pint("curatorId")
    val tags = strings("tags")
    val mods = strings("mods")
    val suggestions = strings("suggestions")
    val requirements = strings("requirements")
    val nps = pfloats("nps")
    val fullSpread = boolean("fullSpread")
    val bpm = pfloat("bpm")
    val duration = pint("duration")
}
