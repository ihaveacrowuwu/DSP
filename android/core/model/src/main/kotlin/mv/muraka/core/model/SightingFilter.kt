package mv.muraka.core.model

import java.time.Instant
import java.util.Locale

/** Which end of the history the contributor wants to see first. */
enum class SightingSort(val label: String) {
    NEWEST_FIRST("Newest first"),
    OLDEST_FIRST("Oldest first"),
    ;

    fun next(): SightingSort = if (this == NEWEST_FIRST) OLDEST_FIRST else NEWEST_FIRST
}

/**
 * What the contributor is looking for in their own history.
 *
 * Applied **locally**, to the merged list, and that is the whole design: a contributor on a
 * boat can search and filter everything the device knows about, with no network at all. The
 * API does support `from`/`to`/`condition` query parameters, and using them would have been
 * the obvious thing — but then filtering would stop working the moment the signal did, on a
 * screen whose entire purpose is to work offline (NFR7).
 *
 * The cost is that filtering only ever sees what has been synced down plus what is still
 * queued, which is exactly the set the contributor has any business searching.
 *
 * An empty filter matches everything, so [isActive] is what the interface uses to decide
 * whether to offer a "clear" affordance and whether an empty result means "nothing matches"
 * rather than "you have no sightings".
 */
data class SightingFilter(
    /** Free text. Matches the site name, the note and the coordinate. */
    val query: String = "",
    /** Inclusive lower bound on capture time. */
    val from: Instant? = null,
    /** Inclusive upper bound on capture time. */
    val to: Instant? = null,
    /** Null means any condition. */
    val condition: Condition? = null,
    /** Empty means any status. Filters on what the contributor *sees*, not on wire values. */
    val statuses: Set<SightingDisplayStatus> = emptySet(),
    /** Null means either. Researchers care about this distinction, and so might a diver. */
    val locationSource: LocationSource? = null,
    val sort: SightingSort = SightingSort.NEWEST_FIRST,
) {
    /** Whether anything is actually being filtered — sort order alone does not count. */
    val isActive: Boolean
        get() = query.isNotBlank() ||
            from != null ||
            to != null ||
            condition != null ||
            statuses.isNotEmpty() ||
            locationSource != null

    /** How many criteria are on, for a badge on the filter control. */
    val activeCriteriaCount: Int
        get() = listOf(
            query.isNotBlank(),
            from != null || to != null,
            condition != null,
            statuses.isNotEmpty(),
            locationSource != null,
        ).count { it }

    fun cleared(): SightingFilter = SightingFilter(sort = sort)

    /** Toggles one status in or out of the set. */
    fun toggling(status: SightingDisplayStatus): SightingFilter =
        copy(statuses = if (status in statuses) statuses - status else statuses + status)

    fun matches(sighting: ContributorSighting): Boolean = matchesQuery(sighting) &&
        matchesDate(sighting) &&
        matchesCondition(sighting) &&
        matchesStatus(sighting) &&
        matchesLocationSource(sighting)

    /** Filters and sorts in one pass. */
    fun apply(sightings: List<ContributorSighting>): List<ContributorSighting> {
        val matching = sightings.filter(::matches)
        return when (sort) {
            SightingSort.NEWEST_FIRST -> matching.sortedByDescending { it.capturedAt }
            SightingSort.OLDEST_FIRST -> matching.sortedBy { it.capturedAt }
        }
    }

    // ── The individual criteria ─────────────────────────────────────────────

    /**
     * Free text over the site name, the note and the coordinate.
     *
     * The coordinate is included because it is often the only thing that distinguishes two
     * sightings from the same dive, and a contributor reading a researcher's email about
     * "the one at 4.17, 73.50" has nothing else to search for. Matched against the same
     * four-decimal rendering the list shows, so what is on screen is what is searchable.
     */
    private fun matchesQuery(sighting: ContributorSighting): Boolean {
        val needle = query.trim().lowercase(Locale.UK)
        if (needle.isEmpty()) return true

        val haystacks = listOfNotNull(
            sighting.server?.siteName,
            sighting.server?.note,
            String.format(Locale.UK, "%.4f, %.4f", sighting.position.lat, sighting.position.lon),
            sighting.displayStatus.label,
        )
        return haystacks.any { it.lowercase(Locale.UK).contains(needle) }
    }

    /**
     * Bounds are compared against **capture** time, not the time the server received it.
     *
     * A diver looking for "last Tuesday" means the day they were in the water, which for a
     * sighting that sat in the outbox for a week is not the day it was uploaded.
     */
    private fun matchesDate(sighting: ContributorSighting): Boolean {
        from?.let { if (sighting.capturedAt < it) return false }
        to?.let { if (sighting.capturedAt > it) return false }
        return true
    }

    /**
     * The **effective** condition — an expert's label where one exists, otherwise the
     * model's. A sighting with no assessment yet matches no condition filter, because it
     * genuinely is neither rather than being both.
     */
    private fun matchesCondition(sighting: ContributorSighting): Boolean =
        condition == null || sighting.server?.condition == condition

    private fun matchesStatus(sighting: ContributorSighting): Boolean =
        statuses.isEmpty() || sighting.displayStatus in statuses

    private fun matchesLocationSource(sighting: ContributorSighting): Boolean =
        locationSource == null || sighting.locationSource == locationSource
}
