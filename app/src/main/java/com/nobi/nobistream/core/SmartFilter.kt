package com.nobi.nobistream.core

/**
 * v5 Smart Filter — one episode, one card.
 *
 * A channel often posts the SAME episode 3-4 times in different
 * qualities (480p / 720p / 1080p / 4K), so the list showed the same
 * episode four times and Next replayed it in another quality. The
 * Smart Filter groups those copies by episode and keeps only the
 * BEST copy of each episode in the list; the rest are hidden behind
 * a "N copies" badge the user can tap to pick any copy manually.
 *
 * Rules (spec approved 2026-11-17):
 * - Identity = season + episode number parsed from title/caption.
 *   "Naruto Shippuden [S16 Ep 13] Ep 361" is season 16, episode 361.
 *   "Ep 361" and "Episode 361" are the same episode. When several
 *   episode numbers appear, the LAST one in the text wins — the
 *   absolute number conventionally comes last ("[S16 Ep 13] Ep 361"
 *   is episode 361, not 13), so a repost titled just "Ep 361" still
 *   groups with it.
 * - No episode number found = the video is standalone, never merged.
 * - "Part 1" / "Part 2" of one episode are DIFFERENT parts, not
 *   duplicates — the part number is part of the key, so both stay.
 * - Season must match exactly (both absent counts as a match). A
 *   copy with no season marker does not merge with one that has
 *   "S16" — different seasons can reuse episode numbers.
 * - Best copy: highest quality rank first (4K > 1080p > 720p >
 *   480p > 360p > Unknown), then larger file (better bitrate proxy),
 *   then newest post.
 * - Grouping is in-memory over the loaded list; Load More merges new
 *   pages into the same groups, so a duplicate never reappears.
 */
data class EpisodeKey(
    val chatId: Long,
    val season: Int?,
    val episode: Int,
    val part: Int?
)

/** One episode and all its posted copies, best copy first. */
data class EpisodeGroup(
    val key: EpisodeKey?,
    val best: VideoItem,
    val copies: List<VideoItem>
) {
    /** Copies hidden behind the best copy's card (0 = no duplicates). */
    val hiddenCount: Int get() = (copies.size - 1).coerceAtLeast(0)
}

object SmartFilter {

    private val seasonRegex =
        Regex("""(?i)(?:\bS(\d{1,2})\b|\bSeason\s+(\d{1,2})\b)""")
    private val episodeRegex =
        Regex("""(?i)(?:\b(?:Ep\.?|Episode|E)\s*(\d{1,4})\b|S\d{1,2}E(\d{1,4})\b)""")
    private val compactSeasonEpisodeRegex =
        Regex("""(?i)\bS(\d{1,2})E(\d{1,4})\b""")
    private val partRegex = Regex("""(?i)\bPart\s*(\d{1,2})\b""")

    /** Best copy first: quality rank, then file size, then newest. */
    private val bestFirst = compareByDescending<VideoItem> { it.quality.rank }
        .thenByDescending { it.sizeBytes }
        .thenByDescending { it.dateEpochSec }

    /**
     * v9.9 Preferred quality: the highest quality rank the preference
     * allows. "Highest available" = no cap. Anything else caps at
     * that quality's rank (4K = 6, 1080p = 4, 720p = 3, 480p = 2).
     * Unknown labels fall back to no cap — never block a video.
     */
    fun maxRankFor(preferred: String): Int = when (preferred) {
        "4K" -> VideoQuality.Q2160.rank
        "1080p" -> VideoQuality.Q1080.rank
        "720p" -> VideoQuality.Q720.rank
        "480p" -> VideoQuality.Q480.rank
        else -> Int.MAX_VALUE
    }

    /**
     * v9.9: the best copy under the cap — the best copy whose rank is
     * at or below [maxRank], or the overall best when NO copy qualifies
     * (an episode with only a 4K copy still plays under a 1080p
     * preference — the cap never blocks a video).
     */
    private fun cappedBest(sorted: List<VideoItem>, maxRank: Int): VideoItem =
        sorted.firstOrNull { it.quality.rank <= maxRank } ?: sorted[0]

    /** Episode identity of one video, or null when it has no episode number. */
    fun parseKey(item: VideoItem): EpisodeKey? {
        val text = "${item.title}\n${item.caption}"
        // (position in text, episode number) from every marker form.
        val numbered = mutableListOf<Pair<Int, Int>>()
        episodeRegex.findAll(text).forEach { match ->
            val number = match.groupValues.getOrNull(1)?.toIntOrNull()
                ?: match.groupValues.getOrNull(2)?.toIntOrNull()
            if (number != null) numbered.add(match.range.first to number)
        }
        compactSeasonEpisodeRegex.findAll(text).forEach { match ->
            match.groupValues.getOrNull(2)?.toIntOrNull()?.let { number ->
                numbered.add(match.range.first to number)
            }
        }
        // Last marker in the text wins (the absolute number comes last).
        val episode = numbered.maxByOrNull { it.first }?.second ?: return null
        val compact = compactSeasonEpisodeRegex.find(text)
        val season = compact?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: seasonRegex.find(text)?.let { match ->
                match.groupValues.getOrNull(1)?.toIntOrNull()
                    ?: match.groupValues.getOrNull(2)?.toIntOrNull()
            }
        val part = partRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return EpisodeKey(item.chatId, season, episode, part)
    }

    /**
     * Group a loaded list into episodes. Videos without an episode
     * number each get their own group. Groups are ordered by where
     * their BEST copy sits in the original list, so a newest-first
     * channel list stays newest-first after dedup.
     */
    fun groupEpisodes(
        items: List<VideoItem>,
        maxRank: Int = Int.MAX_VALUE
    ): List<EpisodeGroup> {
        val byKey = LinkedHashMap<Any, MutableList<VideoItem>>()
        for (item in items) {
            val key = parseKey(item)
            // Null key = standalone: one group per video, never merged.
            val mapKey: Any = key ?: "standalone:${item.chatId}:${item.messageId}"
            byKey.getOrPut(mapKey) { mutableListOf() }.add(item)
        }
        val indexOf = HashMap<Pair<Long, Long>, Int>(items.size)
        items.forEachIndexed { index, video ->
            indexOf[video.chatId to video.messageId] = index
        }
        return byKey.values.map { members ->
            val sorted = members.sortedWith(bestFirst)
            // v9.9: the capped best leads the copies list, so the
            // copies sheet lists the copy that will play first and
            // marks it Best; every copy is still listed and directly
            // pickable (a direct pick ignores the preference).
            val best = cappedBest(sorted, maxRank)
            val copies = listOf(best) + sorted.filter { it !== best }
            EpisodeGroup(parseKey(sorted[0]), best, copies)
        }.sortedBy { group ->
            indexOf[group.best.chatId to group.best.messageId] ?: Int.MAX_VALUE
        }
    }

    /** v9.1 #15: why the best copy won — shown on the copies sheet's
     * "Best" pill. Mirrors [bestFirst] exactly (rank, then size, then
     * date): the best copy beats the second-best on the FIRST criterion
     * where they differ. */
    enum class BestReason { QUALITY, SIZE, NEWEST, PREFERRED }

    fun bestReasonFor(group: EpisodeGroup): BestReason {
        val copies = group.copies
        if (copies.size < 2) return BestReason.QUALITY
        val best = copies[0]
        val second = copies[1]
        return when {
            // v9.9: the best copy is NOT the highest-rank copy in the
            // group — a higher copy exists below it, so the best won
            // because of the Preferred quality cap, not its quality.
            best.quality.rank != copies.maxOf { it.quality.rank } -> BestReason.PREFERRED
            best.quality.rank != second.quality.rank -> BestReason.QUALITY
            best.sizeBytes != second.sizeBytes -> BestReason.SIZE
            else -> BestReason.NEWEST
        }
    }

    /**
     * The deduped list: one best copy per episode, in channel order,
     * each carrying its hidden-copy count for the card badge.
     */
    fun dedup(
        items: List<VideoItem>,
        maxRank: Int = Int.MAX_VALUE
    ): List<VideoItem> =
        groupEpisodes(items, maxRank).map { group ->
            group.best.copy(hiddenCopies = group.hiddenCount)
        }
}
