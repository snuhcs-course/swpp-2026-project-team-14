package com.swpp.stylemate.data

import com.swpp.stylemate.ui.profile.BodyProfileViewModel
import org.junit.Assert.*
import org.junit.Test

class ExistingOnboardingTest {
    @Test fun serverPreferencesSkipARepeatedOnboardingButDefaultsDoNot() {
        val existing = InMemoryBodyProfileStore().apply { saveStyle(StyleProfile(preferredStyles = listOf("casual"))) }
        assertTrue(BodyProfileViewModel(FakeBodyAnalyzer(0), existing).onboarding.value.completed)
        val fresh = InMemoryBodyProfileStore()
        assertFalse(BodyProfileViewModel(FakeBodyAnalyzer(0), fresh).onboarding.value.completed)
        assertTrue(fresh.loadStyle().favoriteColors.isEmpty())
        fresh.save(profile())
        assertTrue(BodyProfileViewModel(FakeBodyAnalyzer(0), fresh).onboarding.value.completed)
    }

    @Test fun anUnfinishedNewAccountDraftIsPreserved() {
        val store = InMemoryBodyProfileStore().apply {
            saveStyle(StyleProfile(preferredFits = listOf("wide")))
            saveOnboarding(OnboardingProgress(3, false, """{"preferred_styles":["casual"]}"""))
        }
        val model = BodyProfileViewModel(FakeBodyAnalyzer(0), store)
        assertFalse(model.onboarding.value.completed)
        assertEquals(3, model.onboarding.value.step)
        assertTrue(model.onboarding.value.draft.contains("casual"))
        assertEquals(listOf("wide"), store.loadStyle().preferredFits)
    }

    @Test fun confirmingBodyInformationDoesNotDiscardAnActiveGarmentDraft() {
        val store = InMemoryBodyProfileStore().apply {
            save(profile())
            saveOnboarding(OnboardingProgress(7, false, """{"garment":{"category":"top","name":"수정 중인 셔츠"}}"""))
        }
        val model = BodyProfileViewModel(FakeBodyAnalyzer(0), store)
        assertFalse(model.onboarding.value.completed)
        assertEquals(7, model.onboarding.value.step)
        assertTrue(model.onboarding.value.draft.contains("수정 중인 셔츠"))
        assertEquals(profile(), store.load())
    }

    private fun profile() = BodyProfile(AnalysisInput(170, null, Gender.UNSPECIFIED), emptyList(),
        PreferredFit.REGULAR, emptyList())
}
