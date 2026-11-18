package com.swpp.stylemate.profile

import androidx.lifecycle.ViewModelStore
import com.swpp.stylemate.data.StyleProfile
import com.swpp.stylemate.ui.profile.ProfilePreference
import com.swpp.stylemate.ui.profile.ProfilePreferencesViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilePreferencesViewModelTest {
    private val store = ViewModelStore()
    private lateinit var model: ProfilePreferencesViewModel

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        model = ProfilePreferencesViewModel()
        store.put("editor", model)
    }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    @Test fun choosingARepresentativeFitReordersAndPreservesEveryOtherSavedFit() {
        val original = StyleProfile(preferredFits = listOf("regular", "wide", "slim"))
        model.open(ProfilePreference.FIT, original)
        model.select("wide")
        assertEquals(listOf("wide", "regular", "slim"), model.state.value.selected)
        assertEquals(listOf("regular", "wide", "slim"), original.preferredFits)
        model.discard()
        model.open(ProfilePreference.FIT, original)
        assertEquals(original.preferredFits, model.state.value.selected)
        assertFalse(model.state.value.changed)
    }

    @Test fun failedSaveRetainsTheDraftAndBlocksEditingDiscardAndDuplicateSaveWhilePending() {
        val gate = CompletableDeferred<String?>()
        var calls = 0
        model.open(ProfilePreference.MOOD, StyleProfile(preferredStyles = listOf("minimal")))
        model.select("cityboy")
        model.save { _, _ -> calls++; gate.await() }
        assertTrue(model.state.value.saving)
        model.select("sporty")
        model.discard()
        model.save { _, _ -> calls++; null }
        assertEquals(1, calls)
        gate.complete("연결 오류")
        assertFalse(model.state.value.saving)
        assertEquals("연결 오류", model.state.value.error)
        assertEquals(listOf("minimal", "cityboy"), model.state.value.selected)
        var written: List<String>? = null
        model.save { section, values ->
            assertEquals(ProfilePreference.MOOD, section)
            calls++; written = values; null
        }
        assertEquals(2, calls)
        assertEquals(listOf("minimal", "cityboy"), written)
        assertNull(model.state.value.section)
        assertNotNull(model.state.value.notice)
    }

    @Test fun colorsCanBeClearedAndAreLimitedWithoutLosingTheOtherDraftValues() {
        val colors = (0..7).map { "#00000$it" }
        model.open(ProfilePreference.COLORS, StyleProfile(favoriteColors = colors))
        model.select("#FFFFFF")
        assertEquals(colors, model.state.value.selected)
        colors.forEach(model::select)
        assertTrue(model.state.value.selected.isEmpty())
        model.save { section, values ->
            assertEquals(ProfilePreference.COLORS, section)
            assertTrue(values.isEmpty()); null
        }
        assertNull(model.state.value.section)
    }

    @Test fun combinedTasteIsWrittenTogetherAndRetainedAfterFailure() {
        val original = StyleProfile(preferredFits = listOf("regular", "slim"), preferredStyles = listOf("minimal"))
        model.open(ProfilePreference.TASTE, original)
        model.selectFit("wide")
        model.toggleMood("cityboy")
        model.saveTaste { fits, moods ->
            assertEquals(listOf("wide", "regular", "slim"), fits)
            assertEquals(listOf("minimal", "cityboy"), moods)
            "연결 오류"
        }
        assertTrue(model.state.value.changed)
        assertFalse(model.state.value.saving)
        assertNotNull(model.state.value.error)
        var writes = 0
        model.saveTaste { fits, moods ->
            writes++
            assertEquals(listOf("wide", "regular", "slim"), fits)
            assertEquals(listOf("minimal", "cityboy"), moods)
            null
        }
        assertEquals(1, writes)
        assertNull(model.state.value.section)
        assertEquals(listOf("regular", "slim"), original.preferredFits)
        assertEquals(listOf("minimal"), original.preferredStyles)
    }

}
