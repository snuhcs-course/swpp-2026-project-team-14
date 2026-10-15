// AI-generated with Codex, 2026-10-16
package com.swpp.stylemate.data

import com.swpp.stylemate.ui.profile.BodyProfileViewModel
import com.swpp.stylemate.ui.profile.SetupStep
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import com.swpp.stylemate.data.recommendation.OutfitRepository
import com.swpp.stylemate.data.wardrobe.WardrobeRepository

class LocalUserStoreTest {
    @get:Rule val temporary = TemporaryFolder(File("build/local-store-tests").apply { mkdirs() })
    private fun store() = LocalUserStore.inDirectory(temporary.root)
    private fun draft(store: LocalUserStore) = store.createGarment(
        JSONObject("""{"attributes":{"name":"티셔츠","category":"top","styles":[]}}"""), byteArrayOf(1, 2, 3),
    )

    @Test fun preferencesSurviveReopeningWithoutBodyAnalysis() {
        val style = StyleProfile(listOf("oversized", "wide"), listOf("minimal", "cityboy"), listOf("#1F2A44"))
        store().saveStyle(style)
        assertEquals(style, store().style())
        assertNull(LocalBodyProfileStore(store()).load())
    }

    @Test fun newUsersHaveEmptyColorsAndDefaultFit() {
        assertEquals(listOf("regular"), store().style().preferredFits)
        assertEquals(emptyList<String>(), store().style().favoriteColors)
    }

    @Test fun skippingSetupIsNotLostWhenAppReopens() {
        val first = BodyProfileViewModel(FakeBodyAnalyzer(0), LocalBodyProfileStore(store()))
        first.skipSetup()
        val reopened = BodyProfileViewModel(FakeBodyAnalyzer(0), LocalBodyProfileStore(store()))
        assertFalse(reopened.app.value.setupVisible)
        assertNull(reopened.app.value.profile)
    }

    @Test fun inputDraftResumesWithoutPersistingPhotos() {
        val first = BodyProfileViewModel(FakeBodyAnalyzer(0), LocalBodyProfileStore(store()))
        first.startCapture()
        first.setHeight("175")
        first.setWeight("68")
        first.setGender(Gender.MALE)
        val reopened = BodyProfileViewModel(FakeBodyAnalyzer(0), LocalBodyProfileStore(store()))
        assertEquals(SetupStep.INPUT, reopened.setup.value.step)
        assertEquals("175", reopened.setup.value.heightText)
        assertEquals("68", reopened.setup.value.weightText)
        assertEquals(Gender.MALE, reopened.setup.value.gender)
        assertNull(reopened.setup.value.frontPhoto)
        assertNull(reopened.setup.value.sidePhoto)
        assertTrue(reopened.app.value.setupVisible)
    }

    @Test fun deletingBodyDoesNotDeletePreferencesOrOnboardingCompletion() {
        val adapter = LocalBodyProfileStore(store())
        val style = StyleProfile(listOf("wide"), listOf("minimal"))
        adapter.saveStyle(style)
        adapter.saveOnboarding(OnboardingProgress(8, true))
        adapter.save(BodyProfile(AnalysisInput(175, null, Gender.UNSPECIFIED), emptyList(), PreferredFit.SLIM, emptyList()))
        adapter.save(null)
        assertNull(adapter.load())
        assertEquals(style, adapter.loadStyle())
        assertTrue(adapter.loadOnboarding().completed)
    }

    @Test fun savedGarmentsAndPhotosSurviveReopeningAndSavingAgainDoesNotDuplicate() {
        val first = store()
        val record = draft(first)
        val id = record.getString("id")
        assertTrue(first.garments().isEmpty())
        val payload = JSONObject().put("attributes", record.getJSONObject("attributes")).put("notes", "처음 메모")
        first.saveGarment(id, payload)
        val reopened = store()
        assertEquals(1, reopened.garments().size)
        assertArrayEquals(byteArrayOf(1, 2, 3), reopened.image("local:$id"))
        reopened.saveGarment(id, JSONObject().put("notes", "수정한 메모"))
        assertEquals(1, store().garments().size)
        assertEquals("수정한 메모", store().garment(id)!!.getString("notes"))
    }

    @Test fun guestAndAccountsHaveSeparateProfilesGarmentsAndPhotos() {
        val account = UUID.randomUUID().toString()
        val guest = store()
        guest.saveStyle(StyleProfile(preferredStyles = listOf("minimal")))
        val record = draft(guest)
        guest.saveGarment(record.getString("id"), JSONObject().put("notes", "guest"))
        val signedIn = LocalUserStore.inDirectory(temporary.root, account)
        assertTrue(signedIn.garments().isEmpty())
        assertTrue(signedIn.style().preferredStyles.isEmpty())
        signedIn.saveStyle(StyleProfile(preferredStyles = listOf("cityboy")))
        assertEquals(listOf("minimal"), store().style().preferredStyles)
        assertEquals(listOf("cityboy"), LocalUserStore.inDirectory(temporary.root, account).style().preferredStyles)
        assertThrows(Exception::class.java) { signedIn.image(record.getString("image_url")) }
    }

    @Test fun savedOutfitsStaySavedWhenRecommendationReappears() {
        val record = JSONObject().put("id", UUID.randomUUID().toString()).put("saved", false).put("items", JSONArray())
        store().rememberOutfits(JSONArray().put(record))
        store().saveOutfit(record.getString("id"), true)
        store().rememberOutfits(JSONArray().put(record.put("saved", false)))
        assertEquals(1, store().outfits().size)
        store().saveOutfit(record.getString("id"), false)
        assertTrue(store().outfits().isEmpty())
    }

    @Test fun repositoriesReadAndSaveDeviceRecordsWithoutServerConnection() = runBlocking {
        val local = store()
        val garment = draft(local)
        WardrobeRepository.json("/api/wardrobe/items/${garment.getString("id")}/",
            JSONObject().put("notes", "offline"), local)
        assertEquals(1, WardrobeRepository.json("/api/wardrobe/items/", store = local).getJSONArray("items").length())
        val outfit = JSONObject().put("id", UUID.randomUUID().toString()).put("items", JSONArray()).put("saved", false)
        local.rememberOutfits(JSONArray().put(outfit))
        val repository = OutfitRepository("http://127.0.0.1:1", local = local)
        repository.setSaved(outfit.getString("id"), true)
        assertEquals(1, repository.saved().size)
        repository.setSaved(outfit.getString("id"), false)
        assertTrue(repository.saved().isEmpty())
    }

    @Test fun malformedSnapshotsAreNotSilentlyOverwritten() {
        val guest = File(temporary.root, "guest").apply { mkdirs() }
        val file = File(guest, "data.json").apply { writeText("broken snapshot") }
        assertThrows(IllegalStateException::class.java) { store().saveStyle(StyleProfile()) }
        assertEquals("broken snapshot", file.readText())
        val model = BodyProfileViewModel(FakeBodyAnalyzer(0), LocalBodyProfileStore(store()))
        assertNotNull(model.app.value.storageError)
        assertEquals("broken snapshot", file.readText())
    }

    @Test fun cancellingDraftDoesNotRemoveSavedGarments() {
        val local = store()
        val cancelled = draft(local)
        local.discardDraft(cancelled.getString("id"))
        assertNull(store().garment(cancelled.getString("id")))
        assertThrows(Exception::class.java) { store().image(cancelled.getString("image_url")) }
        val saved = draft(local)
        local.saveGarment(saved.getString("id"), JSONObject().put("notes", "saved"))
        local.discardDraft(saved.getString("id"))
        assertEquals(1, store().garments().size)
        assertArrayEquals(byteArrayOf(1, 2, 3), store().image(saved.getString("image_url")))
    }

    @Test fun scopeAndImagePathsCannotEscapePrivateDirectory() {
        assertThrows(IllegalArgumentException::class.java) { LocalUserStore.inDirectory(temporary.root, "../other") }
        assertThrows(IllegalArgumentException::class.java) { store().image("local:../other") }
    }
}
