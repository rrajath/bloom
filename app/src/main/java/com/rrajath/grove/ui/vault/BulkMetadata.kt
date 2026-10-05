package com.rrajath.grove.ui.vault

import com.rrajath.grove.org.OrgHeadline
import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.org.Planning

/**
 * What the metadata sheet shows for several headings at once: each value they
 * all share, nothing where they differ.
 */
data class BulkMetadataSummary(
    /**
     * Stand-in for the sheet's single headline: the shared keyword, priority,
     * dates and tags (only those on every heading), an `:ID:` only when every
     * heading has one, and "N notes" as its title.
     */
    val headline: OrgHeadline,
    /** The headings' keywords differ, so no state chip is current. */
    val stateMixed: Boolean,
    /** The headings' priorities differ, so no priority chip is current. */
    val priorityMixed: Boolean,
)

fun bulkMetadataSummary(headlines: List<OrgHeadline>): BulkMetadataSummary? {
    val first = headlines.firstOrNull() ?: return null
    fun <T> shared(value: (OrgHeadline) -> T): T? = headlines.map(value).distinct().singleOrNull()
    fun mixed(value: (OrgHeadline) -> Any?) = headlines.map(value).distinct().size > 1
    return BulkMetadataSummary(
        headline = first.copy(
            keyword = shared { it.keyword },
            priority = shared { it.priority },
            title = "${headlines.size} notes",
            tags = first.tags.filter { tag -> headlines.all { tag in it.tags } },
            planning = Planning(
                scheduled = shared { it.planning.scheduled },
                deadline = shared { it.planning.deadline },
            ),
            dedicatedActiveTimestamps = shared { it.dedicatedActiveTimestamps }.orEmpty(),
            properties = first.id?.takeIf { headlines.all { h -> h.id != null } }
                ?.let { mapOf("ID" to it) }
                .orEmpty(),
        ),
        stateMixed = mixed { it.keyword },
        priorityMixed = mixed { it.priority },
    )
}

/**
 * A Dates screen result applied to several headings: only the fields the user
 * changed from what the sheet showed ([BulkMetadataSummary.headline]) are set
 * on every heading; the rest keep each heading's own value, so setting a
 * SCHEDULED date never wipes the headings' differing DEADLINEs.
 */
data class BulkPlanningEdit(
    val scheduled: OrgTimestamp?,
    val deadline: OrgTimestamp?,
    val active: List<OrgTimestamp>,
    val setScheduled: Boolean,
    val setDeadline: Boolean,
    val setActive: Boolean,
) {
    val isEmpty: Boolean get() = !setScheduled && !setDeadline && !setActive

    /** [h]'s SCHEDULED, DEADLINE and dedicated active timestamps after this edit. */
    fun applyTo(h: OrgHeadline): Triple<OrgTimestamp?, OrgTimestamp?, List<OrgTimestamp>> = Triple(
        if (setScheduled) scheduled else h.planning.scheduled,
        if (setDeadline) deadline else h.planning.deadline,
        if (setActive) active else h.dedicatedActiveTimestamps,
    )

    companion object {
        fun between(
            shown: OrgHeadline,
            scheduled: OrgTimestamp?,
            deadline: OrgTimestamp?,
            active: List<OrgTimestamp>,
        ) = BulkPlanningEdit(
            scheduled, deadline, active,
            setScheduled = scheduled != shown.planning.scheduled,
            setDeadline = deadline != shown.planning.deadline,
            setActive = active != shown.dedicatedActiveTimestamps,
        )
    }
}
