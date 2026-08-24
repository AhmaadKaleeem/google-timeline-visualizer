package dev.mahlernim.timelinevisualizer.journal.onboarding

import androidx.annotation.StringRes
import dev.mahlernim.timelinevisualizer.R

data class JournalOnboardingPage(
    @param:StringRes val titleRes: Int,
    @param:StringRes val bodyRes: Int,
    @param:StringRes val noteRes: Int? = null,
    val illustration: JournalOnboardingIllustration,
)

enum class JournalOnboardingIllustration {
    JOURNAL,
    SOURCE,
    LAYERS,
    PRESERVE,
    START,
}

object JournalOnboardingPages {
    val all = listOf(
        JournalOnboardingPage(
            R.string.onboarding_journal_title,
            R.string.onboarding_journal_body,
            illustration = JournalOnboardingIllustration.JOURNAL,
        ),
        JournalOnboardingPage(
            R.string.onboarding_source_title,
            R.string.onboarding_source_body,
            R.string.onboarding_source_note,
            JournalOnboardingIllustration.SOURCE,
        ),
        JournalOnboardingPage(
            R.string.onboarding_layers_title,
            R.string.onboarding_layers_body,
            R.string.onboarding_layers_note,
            JournalOnboardingIllustration.LAYERS,
        ),
        JournalOnboardingPage(
            R.string.onboarding_preserve_title,
            R.string.onboarding_preserve_body,
            R.string.onboarding_preserve_note,
            JournalOnboardingIllustration.PRESERVE,
        ),
        JournalOnboardingPage(
            R.string.onboarding_start_title,
            R.string.onboarding_start_body,
            R.string.onboarding_start_note,
            JournalOnboardingIllustration.START,
        ),
    )
}
