package dev.mahlernim.timelinevisualizer.journal

enum class JournalEntryDestination {
    VIDEOS,
    CREATE,
    JOURNAL_SETUP,
}

/** Keeps Journal onboarding decisions separate from video customization Settings. */
object JournalSetupNavigation {
    fun defaultDestination(isJournalLab: Boolean, hasUsableJournal: Boolean): JournalEntryDestination =
        if (isJournalLab && !hasUsableJournal) {
            JournalEntryDestination.JOURNAL_SETUP
        } else {
            JournalEntryDestination.VIDEOS
        }

    fun createDestination(isJournalLab: Boolean, hasUsableJournal: Boolean): JournalEntryDestination =
        if (isJournalLab && !hasUsableJournal) {
            JournalEntryDestination.JOURNAL_SETUP
        } else {
            JournalEntryDestination.CREATE
        }
}
