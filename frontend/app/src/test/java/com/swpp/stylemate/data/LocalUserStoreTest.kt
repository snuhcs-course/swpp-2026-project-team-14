package com.swpp.stylemate.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class LocalUserStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val firstId = "00000000-0000-0000-0000-000000000001"
    private val secondId = "00000000-0000-0000-0000-000000000002"
    private fun store(id: String = firstId) = LocalUserStore.inDirectory(temporary.root, id)

    @Test fun accountCachesAndOnboardingDraftsRemainIsolatedAndSurviveReopening() {
        val first = store()
        first.saveStyle(StyleProfile(preferredStyles = listOf("minimal")))
        first.saveOnboarding(OnboardingProgress(4, draft = "{\"height\":\"170\"}"))
        first.put("taste", JSONObject().put("color:black", 0.3))
        assertEquals(listOf("minimal"), store().style().preferredStyles)
        assertEquals(4, store().onboarding().step)
        assertEquals(0.3, store().get("taste")!!.getDouble("color:black"), 0.0)
        assertTrue(store(secondId).style().preferredStyles.isEmpty())
        assertNull(store(secondId).get("taste"))
        assertFalse(store(secondId).onboarding().completed)
    }

    @Test fun refreshingAccountProfilePreservesOnboardingAndNeverSkipsNewAccountSetup() {
        val user = AccountUser(firstId, "member_1", null, StyleProfile(preferredStyles = listOf("cityboy")))
        store().cacheAccountProfile(user)
        assertFalse(store().onboarding().completed)
        store().saveOnboarding(OnboardingProgress(3, draft = "{\"preferred_fits\":[]}"))
        store().cacheAccountProfile(user)
        assertEquals(3, store().onboarding().step)
        assertEquals("{\"preferred_fits\":[]}", store().onboarding().draft)
        store().saveOnboarding(OnboardingProgress(8, true))
        store().cacheAccountProfile(user)
        assertTrue(store().onboarding().completed)
    }

    @Test fun accountRefreshCanRemoveBodyWithoutRemovingOtherAccountState() {
        store().put("body", JSONObject().put("height_cm", 170))
        store().put("taste", JSONObject().put("x", 1))
        store().cacheAccountProfile(AccountUser(firstId, "member_1", null, StyleProfile()))
        assertNull(store().get("body"))
        assertEquals(1, store().get("taste")!!.getInt("x"))
    }

    @Test fun garmentListRefreshReplacesOnlyTheCurrentAccountsCache() {
        fun row(id: String) = JSONObject().put("id", id).put("saved", true)
        store().cacheGarments(JSONArray().put(row("old")))
        store(secondId).cacheGarments(JSONArray().put(row("other")))
        store().cacheGarments(JSONArray().put(row("new")))
        assertEquals("new", store().garments().single().getString("id"))
        assertEquals("other", store(secondId).garments().single().getString("id"))
    }

    @Test fun corruptCacheIsReportedWithoutOverwritingIt() {
        store().saveStyle(StyleProfile())
        val snapshot = File(File(temporary.root, firstId), "data.json")
        snapshot.writeText("broken")
        assertThrows(IllegalStateException::class.java) { store().style() }
        assertEquals("broken", snapshot.readText())
    }

    @Test fun scopesRequireAnAccountUuidAndColorsRemainExplicitAndValidated() {
        assertThrows(IllegalArgumentException::class.java) { store("../other") }
        assertTrue(store().style().favoriteColors.isEmpty())
        store().saveStyle(StyleProfile(favoriteColors = listOf("#abcdef")))
        assertEquals(listOf("#ABCDEF"), store().style().favoriteColors)
        assertThrows(IllegalArgumentException::class.java) { store().saveStyle(StyleProfile(favoriteColors = listOf("blue"))) }
    }
}
