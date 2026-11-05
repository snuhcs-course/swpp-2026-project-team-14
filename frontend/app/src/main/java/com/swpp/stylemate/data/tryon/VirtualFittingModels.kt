package com.swpp.stylemate.data.tryon

import com.swpp.stylemate.data.AnalysisInput
import com.swpp.stylemate.data.Gender
import kotlin.math.abs
import kotlin.math.roundToInt

data class VirtualFittingModel(val id: String, val gender: Gender, val heightCm: Int, val weightKg: Int, val revision: Int = 1) {
    val assetPath get() = "tryon/models/v1/$id.webp"
    val sourceKey get() = "virtual:$id:v$revision"
    val label get() = "${gender.label} ${heightCm}cm · ${weightKg}kg 기준"
}

/** Representative image proportions, not medical categories or measured body reconstructions. */
object VirtualFittingModels {
    val all: List<VirtualFittingModel> = listOf(
        Triple(Gender.MALE, listOf(160, 170, 180, 190), listOf(19.5, 23.0, 27.0)),
        Triple(Gender.FEMALE, listOf(150, 160, 170, 180), listOf(19.0, 22.0, 26.0)),
    ).flatMap { (gender, heights, ratios) ->
        heights.flatMap { height ->
            ratios.mapIndexed { index, ratio ->
                val build = listOf("slim", "regular", "fuller")[index]
                VirtualFittingModel("${gender.name.lowercase()}_${height}_$build", gender, height,
                    (ratio * height * height / 10_000).roundToInt(), revision = if (index == 2) 2 else 1)
            }
        }
    }

    fun closest(input: AnalysisInput?): VirtualFittingModel? {
        val weight = input?.weightKg ?: return null
        if (input.gender == Gender.UNSPECIFIED || input.heightCm !in 100..220 || weight !in 30..200) return null
        val candidates = all.filter { it.gender == input.gender }
        val height = candidates.minBy { abs(it.heightCm - input.heightCm) }.heightCm
        val ratio = weight * 10_000.0 / (input.heightCm * input.heightCm)
        return candidates.filter { it.heightCm == height }
            .minBy { abs(it.weightKg * 10_000.0 / (it.heightCm * it.heightCm) - ratio) }
    }
}
