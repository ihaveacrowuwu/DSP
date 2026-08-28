package mv.muraka.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The filter is applied locally so that searching works with no connection (NFR7), which
 * means it is ordinary logic and can be tested exhaustively without a server.
 */
class SightingFilterTest {

    private val august = Instant.parse("2026-08-15T09:00:00Z")
    private val july = Instant.parse("2026-07-01T09:00:00Z")
    private val june = Instant.parse("2026-06-01T09:00:00Z")

    @Test
    fun `an empty filter matches everything and is not active`() {
        val filter = SightingFilter()
        assertFalse(filter.isActive)
        assertEquals(0, filter.activeCriteriaCount)
        assertEquals(3, filter.apply(listOf(bleached(), healthy(), queued())).size)
    }

    @Test
    fun `sort order alone does not count as filtering`() {
        // Otherwise the interface would offer to "clear" a filter that filters nothing.
        val filter = SightingFilter(sort = SightingSort.OLDEST_FIRST)
        assertFalse(filter.isActive)
    }

    @Test
    fun `text search matches the site name`() {
        val filter = SightingFilter(query = "banana")
        assertTrue(filter.matches(healthy(siteName = "Banana Reef")))
        assertFalse(filter.matches(healthy(siteName = "Manta Point")))
    }

    @Test
    fun `text search matches the note, case-insensitively`() {
        val filter = SightingFilter(query = "PATCHY")
        assertTrue(filter.matches(healthy(note = "North side, patchy")))
    }

    @Test
    fun `text search matches the coordinate as it is displayed`() {
        // A contributor answering "the one at 4.1755, 73.50" has nothing else to search for,
        // and the list shows four decimals - so four decimals is what must match.
        val filter = SightingFilter(query = "4.1755")
        assertTrue(filter.matches(healthy(lat = 4.17552, lon = 73.5093)))
        assertFalse(filter.matches(healthy(lat = 6.79221, lon = 73.1944)))
    }

    @Test
    fun `text search matches the status the contributor can see`() {
        val filter = SightingFilter(query = "awaiting")
        assertTrue(filter.matches(healthy(status = SightingDisplayStatus.AWAITING_REVIEW)))
        assertFalse(filter.matches(healthy(status = SightingDisplayStatus.VERIFIED_BY_EXPERT)))
    }

    @Test
    fun `a blank query is not a filter`() {
        assertFalse(SightingFilter(query = "   ").isActive)
        assertTrue(SightingFilter(query = "   ").matches(healthy()))
    }

    @Test
    fun `the date range is inclusive at both ends`() {
        val filter = SightingFilter(from = july, to = august)
        assertTrue(filter.matches(healthy(capturedAt = july)))
        assertTrue(filter.matches(healthy(capturedAt = august)))
        assertFalse(filter.matches(healthy(capturedAt = june)))
    }

    @Test
    fun `the date range is compared against capture time, not upload time`() {
        // A sighting that sat in the outbox for a week was still taken on the day the diver
        // was in the water, and that is the day they will search for.
        val capturedInJune = healthy(capturedAt = june).copy(
            server = healthy(capturedAt = june).server?.copy(createdAt = august),
        )
        assertTrue(SightingFilter(from = june, to = july).matches(capturedInJune))
        assertFalse(SightingFilter(from = august).matches(capturedInJune))
    }

    @Test
    fun `filtering by condition uses the effective label`() {
        assertTrue(SightingFilter(condition = Condition.BLEACHED).matches(bleached()))
        assertFalse(SightingFilter(condition = Condition.BLEACHED).matches(healthy()))
    }

    @Test
    fun `an unassessed sighting matches no condition filter`() {
        // It is genuinely neither, rather than being both - a queued sighting the model has
        // not seen must not appear under "Healthy".
        assertFalse(SightingFilter(condition = Condition.HEALTHY).matches(queued()))
        assertFalse(SightingFilter(condition = Condition.BLEACHED).matches(queued()))
    }

    @Test
    fun `status filtering accepts any of the selected statuses`() {
        val filter = SightingFilter(
            statuses = setOf(
                SightingDisplayStatus.WAITING_TO_UPLOAD,
                SightingDisplayStatus.VERIFIED_BY_EXPERT,
            ),
        )
        assertTrue(filter.matches(queued()))
        assertTrue(filter.matches(healthy(status = SightingDisplayStatus.VERIFIED_BY_EXPERT)))
        assertFalse(filter.matches(healthy(status = SightingDisplayStatus.ANALYSING)))
    }

    @Test
    fun `toggling a status adds it and then removes it`() {
        val once = SightingFilter().toggling(SightingDisplayStatus.ANALYSING)
        assertEquals(setOf(SightingDisplayStatus.ANALYSING), once.statuses)
        assertTrue(once.toggling(SightingDisplayStatus.ANALYSING).statuses.isEmpty())
    }

    @Test
    fun `location source separates a GPS fix from a dropped pin`() {
        assertTrue(SightingFilter(locationSource = LocationSource.MANUAL_PIN).matches(queued()))
        assertFalse(SightingFilter(locationSource = LocationSource.GPS).matches(queued()))
    }

    @Test
    fun `criteria combine, they do not compete`() {
        val filter = SightingFilter(
            query = "banana",
            condition = Condition.BLEACHED,
            from = july,
        )
        assertEquals(3, filter.activeCriteriaCount)
        assertTrue(filter.matches(bleached(siteName = "Banana Reef", capturedAt = august)))
        // Right name, right date, wrong condition.
        assertFalse(filter.matches(healthy(siteName = "Banana Reef", capturedAt = august)))
        // Right name, right condition, too early.
        assertFalse(filter.matches(bleached(siteName = "Banana Reef", capturedAt = june)))
    }

    @Test
    fun `sorting runs in the requested direction`() {
        val list = listOf(healthy(capturedAt = july), healthy(capturedAt = august), healthy(capturedAt = june))

        assertEquals(
            listOf(august, july, june),
            SightingFilter().apply(list).map { it.capturedAt },
        )
        assertEquals(
            listOf(june, july, august),
            SightingFilter(sort = SightingSort.OLDEST_FIRST).apply(list).map { it.capturedAt },
        )
    }

    @Test
    fun `clearing keeps the sort order but drops every criterion`() {
        val filter = SightingFilter(
            query = "banana",
            condition = Condition.BLEACHED,
            sort = SightingSort.OLDEST_FIRST,
        ).cleared()

        assertFalse(filter.isActive)
        assertEquals(SightingSort.OLDEST_FIRST, filter.sort)
    }

    // -- Fixtures ------------------------------------------------------------

    private fun healthy(
        siteName: String? = "Manta Point",
        note: String? = null,
        capturedAt: Instant = august,
        lat: Double = 4.1755,
        lon: Double = 73.5093,
        status: SightingDisplayStatus = SightingDisplayStatus.AWAITING_REVIEW,
    ) = server(Condition.HEALTHY, siteName, note, capturedAt, lat, lon, status)

    private fun bleached(siteName: String? = "Manta Point", capturedAt: Instant = august) =
        server(Condition.BLEACHED, siteName, null, capturedAt, 4.1755, 73.5093, SightingDisplayStatus.AWAITING_REVIEW)

    private fun server(
        condition: Condition,
        siteName: String?,
        note: String?,
        capturedAt: Instant,
        lat: Double,
        lon: Double,
        status: SightingDisplayStatus,
    ): ContributorSighting {
        val position = Position(lat, lon)
        return ContributorSighting(
            id = "s-$condition-$capturedAt-$lat",
            capturedAt = capturedAt,
            position = position,
            locationSource = LocationSource.GPS,
            photoCount = 1,
            displayStatus = status,
            server = Sighting(
                id = "s",
                contributorId = "diver-a",
                siteName = siteName,
                position = position,
                locationSource = LocationSource.GPS,
                capturedAt = capturedAt,
                note = note,
                status = SightingStatus.AWAITING_VERIFICATION,
                createdAt = capturedAt,
                condition = condition,
            ),
            serverReadAt = capturedAt,
        )
    }

    /** Never sent, so no server record and no assessment. */
    private fun queued() = ContributorSighting(
        id = "queued",
        capturedAt = august,
        position = Position(4.1755, 73.5093),
        locationSource = LocationSource.MANUAL_PIN,
        photoCount = 1,
        displayStatus = SightingDisplayStatus.WAITING_TO_UPLOAD,
        outboxState = OutboxState.QUEUED,
    )
}
