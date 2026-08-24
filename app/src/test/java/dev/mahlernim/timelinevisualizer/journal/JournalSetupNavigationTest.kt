package dev.mahlernim.timelinevisualizer.journal

import org.junit.Assert.assertEquals
import org.junit.Test

class JournalSetupNavigationTest {
    @Test
    fun emptyJournalLabLaunchesFocusedSetup() {
        assertEquals(
            JournalEntryDestination.JOURNAL_SETUP,
            JournalSetupNavigation.defaultDestination(isJournalLab = true, hasUsableJournal = false),
        )
    }

    @Test
    fun existingJournalSkipsLaunchOnboarding() {
        assertEquals(
            JournalEntryDestination.VIDEOS,
            JournalSetupNavigation.defaultDestination(isJournalLab = true, hasUsableJournal = true),
        )
    }

    @Test
    fun createUsesSetupOnlyWhenJournalIsUnavailable() {
        assertEquals(
            JournalEntryDestination.JOURNAL_SETUP,
            JournalSetupNavigation.createDestination(isJournalLab = true, hasUsableJournal = false),
        )
        assertEquals(
            JournalEntryDestination.CREATE,
            JournalSetupNavigation.createDestination(isJournalLab = true, hasUsableJournal = true),
        )
    }

    @Test
    fun productionNavigationIsUnchanged() {
        assertEquals(
            JournalEntryDestination.VIDEOS,
            JournalSetupNavigation.defaultDestination(isJournalLab = false, hasUsableJournal = false),
        )
        assertEquals(
            JournalEntryDestination.CREATE,
            JournalSetupNavigation.createDestination(isJournalLab = false, hasUsableJournal = false),
        )
    }
}
