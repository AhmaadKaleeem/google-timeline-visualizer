package dev.mahlernim.timelinevisualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun mapsSupportedLanguageTagsAndSystemDefault() {
        assertEquals(0, AppLanguage.selectionIndex(""))
        assertEquals(1, AppLanguage.selectionIndex("en-US"))
        assertEquals(2, AppLanguage.selectionIndex("ko"))
        assertEquals(4, AppLanguage.selectionIndex("zh-Hans-CN"))
        assertEquals(5, AppLanguage.selectionIndex("zh-Hant-TW"))
        assertEquals(9, AppLanguage.selectionIndex("pt-BR"))
    }

    @Test
    fun unsupportedLanguageFallsBackToSystemDefault() {
        assertEquals(0, AppLanguage.selectionIndex("it-IT"))
        assertEquals(0, AppLanguage.selectionIndex("pt-PT"))
        assertEquals(0, AppLanguage.selectionIndex("invalid"))
    }

    @Test
    fun everySelectionProducesTheExpectedLocaleList() {
        assertEquals("", AppLanguage.localesForSelection(0).toLanguageTags())
        AppLanguage.supportedTags.forEachIndexed { index, tag ->
            assertEquals(tag, AppLanguage.localesForSelection(index + 1).toLanguageTags())
        }
    }
}
