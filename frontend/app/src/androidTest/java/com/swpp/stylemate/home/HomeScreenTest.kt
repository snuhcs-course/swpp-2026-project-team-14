// AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
package com.swpp.stylemate.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.OutfitGarment
import com.swpp.stylemate.data.recommendation.Reason
import com.swpp.stylemate.data.recommendation.Weather
import com.swpp.stylemate.ui.home.HomeScreen
import com.swpp.stylemate.ui.home.HomeState
import com.swpp.stylemate.ui.theme.StyleMateTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule val compose = createComposeRule()

    private val outfit = Outfit(
        id = "o1", key = "w:a+w:b", score = 78,
        items = listOf(
            OutfitGarment("a", "top", "베이지 니트", "/api/wardrobe/items/a/image/", "내 옷 · 내 몸 기준 루즈 (가슴 여유 16 cm)"),
            OutfitGarment("b", "bottom", "검정 슬랙스", "/api/wardrobe/items/b/image/", "내 옷"),
        ),
        reasons = listOf(Reason("color", "베이지 · 블랙 뉴트럴 조합이라 차분해요.", "배색 규칙")),
        saved = false, name = "", feedback = null, createdAt = "2026-10-12T09:00:00",
    )
    private val weather = Weather(18.0, 17.0, "맑음", "가을 간절기", false, false, JSONObject())

    private fun show(initial: HomeState, events: MutableList<String> = mutableListOf(), smallScreen: Boolean = false) {
        compose.setContent {
            StyleMateTheme {
                var state by remember { mutableStateOf(initial) }
                fun feedback(value: String) {
                    val current = state.current ?: return
                    state = state.copy(outfits = state.outfits.map {
                        if (it.id == current.id) it.copy(feedback = if (it.feedback == value) null else value) else it
                    })
                }
                val modifier = if (smallScreen) Modifier.width(320.dp).height(540.dp) else Modifier.fillMaxSize()
                Box(Modifier.fillMaxSize()) {
                    HomeScreen(state, onRefresh = { events += "refresh" }, onSelect = { state = state.copy(index = it) },
                        onLike = { events += "like"; feedback("liked") }, onDislike = { events += "dislike"; feedback("disliked") },
                        onSave = { events += "save:${state.current?.id}"; state = state.copy(outfits = state.outfits.mapIndexed { index, item ->
                            if (index == state.index) item.copy(saved = !item.saved) else item
                        }) },
                        onSetupProfile = { events += "setup" }, onOpenWardrobe = { events += "wardrobe" },
                        onMessageShown = {}, modifier = modifier.testTag("home"), onChat = { events += "chat:${state.current?.id}" },
                        fitting = { selected, fittingModifier, enabled ->
                            Button(onClick = { events += "fitting:${selected.id}" }, enabled = enabled,
                                modifier = fittingModifier.heightIn(min = 52.dp)) { Text("가상 피팅") }
                        }, photo = { item, photoModifier -> Box(photoModifier) { Text("사진:${item.name}") } })
                }
            }
        }
    }

    @Test fun smallScreenShowsTheWholeOutfitAndPrimaryActionsBeforeScrolling() {
        val events = mutableListOf<String>()
        val layered = outfit.copy(items = outfit.items + OutfitGarment("c", "outerwear", "재킷", "/c", "내 옷"))
        val alternative = outfit.copy(id = "o2", key = "k2", items = outfit.items.map { it.copy(name = "다른 ${it.name}") })
        show(HomeState(weather = weather, outfits = listOf(layered, alternative)), events, smallScreen = true)
        compose.onNodeWithTag("home").assertWidthIsEqualTo(320.dp).assertHeightIsEqualTo(540.dp)
        compose.onNodeWithText("오늘의 코디").assertIsDisplayed()
        compose.onNodeWithText("서울 18°C · 맑음 · 가을 간절기").assertIsDisplayed()
        compose.onNodeWithText("사진:베이지 니트").assertIsDisplayed()
        compose.onNodeWithText("사진:검정 슬랙스").assertIsDisplayed()
        compose.onNodeWithText("사진:재킷").assertIsDisplayed()
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.onNodeWithText("가상 피팅").assertIsDisplayed().performClick()
        compose.onNodeWithText("코디북에 저장").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "저장 안 됨")).performClick()
        compose.onNodeWithText("저장됨").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "저장됨"))
        compose.onNodeWithText("대화로 수정하기").assertIsDisplayed().performClick()
        compose.onNodeWithText("내 옷장에서 100% 구성").assertDoesNotExist()
        compose.onNodeWithText("어울림 78점").assertDoesNotExist()
        compose.onNodeWithText("좋아요").performScrollTo().performClick()
        compose.onNodeWithText("별로예요").performClick()
        compose.runOnIdle { assertEquals(listOf("fitting:o1", "save:o1", "chat:o1", "like", "dislike"), events) }
    }

    @Test fun swipingAndJumpingBetweenOutfitsKeepsActionsOnTheSelectedOutfit() {
        val events = mutableListOf<String>()
        show(HomeState(outfits = listOf(outfit, outfit.copy(id = "o2", key = "k2"), outfit.copy(id = "o3", key = "k3"))), events)
        compose.onNodeWithContentDescription("코디 1 / 3").performTouchInput { swipeLeft() }
        compose.onNodeWithText("2 / 3").assertIsDisplayed()
        compose.onNodeWithContentDescription("코디 2 선택").assertIsSelected()
        compose.onNodeWithText("가상 피팅").performClick()
        compose.onNodeWithText("코디북에 저장").performClick()
        compose.onNodeWithContentDescription("코디 1 선택").performClick()
        compose.onNodeWithText("1 / 3").assertIsDisplayed()
        // Jump over an intermediate page: it must not reset the selection during animation.
        compose.onNodeWithContentDescription("코디 3 선택").performClick()
        compose.onNodeWithText("3 / 3").assertIsDisplayed()
        compose.onNodeWithContentDescription("코디 3 선택").assertIsSelected()
        compose.onNodeWithText("대화로 수정하기").performClick()
        compose.runOnIdle { assertEquals(listOf("fitting:o2", "save:o2", "chat:o3"), events) }
    }

    @Test fun reasonsAndFiveStarRatingAreVisibleWithoutSourcesOrGarmentDetails() {
        val reasons = listOf(
            outfit.reasons.first(),
            Reason("body", "선호하는 루즈핏에 맞췄어요.", "선호 핏 기준"),
            Reason("season", "낮에 더우면 아우터를 벗어도 완성된 코디예요.", "배색 규칙 + K-Fashion"),
        )
        show(HomeState(outfits = listOf(outfit.copy(reasons = reasons)), notes = listOf("날씨에 맞는 옷이 부족해요.")))
        compose.onNodeWithText("이 코디를 고른 이유").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("추천 별점 4.0점 (5점 만점)").assertIsDisplayed().assertHasNoClickAction()
        reasons.forEach { compose.onNodeWithText(it.text).performScrollTo().assertIsDisplayed() }
        reasons.forEach { compose.onNodeWithText(it.sub).assertDoesNotExist() }
        compose.onNodeWithText("코디 정보").assertDoesNotExist()
        compose.onNodeWithText("어울림 78점").assertDoesNotExist()
        compose.onNodeWithText("구성한 옷").assertDoesNotExist()
        compose.onNodeWithText("베이지 니트").assertDoesNotExist()
        compose.onNodeWithText("내 몸 기준 루즈 (가슴 여유 16 cm)").assertDoesNotExist()
        compose.onNodeWithText("날씨에 맞는 옷이 부족해요.").performScrollTo().assertIsDisplayed()
    }

    @Test fun feedbackButtonsShowSelectionAndAllowItToBeCleared() {
        show(HomeState(outfits = listOf(outfit)))
        compose.onNodeWithText("좋아요").performScrollTo().assertIsNotSelected().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("좋아요").assertIsSelected().performClick()
        compose.onNodeWithText("좋아요").assertIsNotSelected()
        compose.onNodeWithText("별로예요").assertIsNotSelected().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("별로예요").assertIsSelected()
        compose.onNodeWithText("좋아요").performClick()
        compose.onNodeWithText("좋아요").assertIsSelected()
        compose.onNodeWithText("별로예요").assertIsNotSelected()
    }

    @Test fun missingScoreDoesNotInventARatingAndKeepsServiceWarnings() {
        show(HomeState(outfits = listOf(outfit.copy(score = null, reasons = emptyList())), notes = listOf("날씨에 맞는 옷이 부족해요.")))
        compose.onAllNodes(hasContentDescription("추천 별점", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("이 코디를 고른 이유").assertDoesNotExist()
        compose.onNodeWithText("날씨에 맞는 옷이 부족해요.").performScrollTo().assertIsDisplayed()
    }

    @Test fun draggingTheCarouselDisablesActionsUntilTheSelectionSettles() {
        val events = mutableListOf<String>()
        show(HomeState(outfits = listOf(outfit, outfit.copy(id = "o2", key = "k2"))), events)
        compose.onNodeWithContentDescription("코디 1 / 2").performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.6f, 0f), delayMillis = 64)
        }
        compose.onNodeWithText("가상 피팅").assertIsNotEnabled()
        compose.onNodeWithText("코디북에 저장").assertIsNotEnabled()
        compose.onNodeWithText("대화로 수정하기").assertIsNotEnabled()
        compose.onNodeWithContentDescription("코디 1 / 2").performTouchInput { up() }
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
        compose.onNodeWithText("가상 피팅").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("fitting:o2"), events) }
    }

    @Test fun failedRefreshKeepsTheOutfitAndOffersRetry() {
        val events = mutableListOf<String>()
        show(HomeState(outfits = listOf(outfit), error = "연결을 확인하고 다시 시도해 주세요."), events)
        compose.onNodeWithText("연결을 확인하고 다시 시도해 주세요.").assertIsDisplayed()
        compose.onNodeWithText("사진:베이지 니트").assertIsDisplayed()
        compose.onNodeWithText("다시 시도").performClick()
        compose.runOnIdle { assertEquals(listOf("refresh"), events) }
    }

    @Test fun aNewSetAfterAnEmptyStateStartsAtItsFirstOutfit() {
        var state by mutableStateOf(HomeState(outfits = listOf(outfit, outfit.copy(id = "o2", key = "k2"))))
        val saved = mutableListOf<String>()
        compose.setContent {
            StyleMateTheme {
                HomeScreen(state, onRefresh = {}, onLike = {}, onDislike = {},
                    onSave = { saved += state.current!!.id }, onSetupProfile = {}, onOpenWardrobe = {},
                    onMessageShown = {}, onSelect = { state = state.copy(index = it) },
                    photo = { _, modifier -> Box(modifier) })
            }
        }
        compose.onNodeWithContentDescription("코디 2 선택").performClick()
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
        compose.runOnIdle { state = HomeState(errorCode = HomeState.NO_PROFILE) }
        compose.onNodeWithText("체형 분석하기").assertIsDisplayed()
        compose.runOnIdle { state = HomeState(outfits = listOf(outfit.copy(id = "new1"), outfit.copy(id = "new2", key = "new2"))) }
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("코디 1 선택").assertIsSelected()
        compose.onNodeWithText("코디북에 저장").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("new1"), saved) }
    }

    @Test fun loadingDisablesSelectionAndActionsUntilTheNewSetArrives() {
        show(HomeState(loading = true, outfits = listOf(outfit, outfit.copy(id = "o2", key = "k2"))))
        compose.onNodeWithText("새로 추천").assertIsNotEnabled()
        compose.onNodeWithText("가상 피팅").assertIsNotEnabled()
        compose.onNodeWithText("코디북에 저장").assertIsNotEnabled()
        compose.onNodeWithContentDescription("코디 2 선택").assertIsNotEnabled()
    }

    @Test fun emptyStatesLeadToTheProfileOrTheWardrobe() {
        val events = mutableListOf<String>()
        show(HomeState(errorCode = HomeState.NO_PROFILE), events)
        compose.onNodeWithText("체형 정보가 필요해요").assertIsDisplayed()
        compose.onNodeWithText("체형 분석하기").performClick()
        compose.runOnIdle { assertEquals(listOf("setup"), events) }
    }

    @Test fun tooFewGarmentsAsksToRegisterMore() {
        val events = mutableListOf<String>()
        show(HomeState(errorCode = "WARDROBE_TOO_SMALL", error = "상의와 하의를 한 벌씩 등록하면 코디를 만들어 드려요."), events)
        compose.onNodeWithText("상의와 하의를 한 벌씩 등록하면 코디를 만들어 드려요.").assertIsDisplayed()
        compose.onNodeWithText("옷장으로 가기").performClick()
        compose.runOnIdle { assertEquals(listOf("wardrobe"), events) }
    }
}
