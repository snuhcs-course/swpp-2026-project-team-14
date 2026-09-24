package com.swpp.stylemate.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Calls the Django body-analysis endpoint (`POST /api/body-profile/analyze/`,
 * docs/body-analysis/02-design.md §7) with the two photos and the user's inputs.
 */
class RemoteBodyAnalyzer(
    baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
) : BodyAnalyzer {

    private val endpoint = baseUrl.trimEnd('/') + "/api/body-profile/analyze/"

    override suspend fun analyze(input: AnalysisInput, photos: BodyPhotos): AnalysisResult =
        withContext(Dispatchers.IO) {
            val jpeg = "image/jpeg".toMediaType()
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("front_photo", "front.jpg", photos.frontJpeg.toRequestBody(jpeg))
                .addFormDataPart("side_photo", "side.jpg", photos.sideJpeg.toRequestBody(jpeg))
                .addFormDataPart("height_cm", input.heightCm.toString())
                .addFormDataPart("gender", input.gender.name.lowercase())
                .addFormDataPart("clothing", input.clothing.name.lowercase())
                .apply { input.weightKg?.let { addFormDataPart("weight_kg", it.toString()) } }
                .build()
            val request = Request.Builder().url(endpoint).post(body).build()

            val (status, text) = try {
                client.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
            } catch (e: IOException) {
                throw BodyAnalysisException(NETWORK_ERROR, "network", e)
            }
            parseResponse(status, text)
        }

    companion object {
        const val NETWORK_ERROR = "서버에 연결하지 못했어요. 인터넷 연결을 확인하고 다시 시도해주세요."
        const val SERVER_ERROR = "분석 중 문제가 생겼어요. 잠시 후 다시 시도해주세요."

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS) // first request loads the pose model
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        /** Maps an HTTP status + JSON body to a result, or throws with the server's Korean hint. */
        fun parseResponse(status: Int, text: String): AnalysisResult {
            val json = try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw BodyAnalysisException(SERVER_ERROR, "invalid_response", e)
            }
            if (status != 200) {
                val hint = json.optString("hint").ifBlank { SERVER_ERROR }
                throw BodyAnalysisException(hint, json.optString("error").ifBlank { null })
            }
            val byKey = MeasurementType.entries.associateBy { it.apiKey }
            val items = json.getJSONArray("measurements")
            val measurements = (0 until items.length()).mapNotNull { i ->
                val item = items.getJSONObject(i)
                val type = byKey[item.getString("type")] ?: return@mapNotNull null
                BodyMeasurement(
                    type = type,
                    valueCm = item.getDouble("value_cm"),
                    confidence = Confidence.valueOf(item.getString("confidence").uppercase()),
                )
            }
            val codes = json.optJSONArray("warnings")
            val warnings = (0 until (codes?.length() ?: 0)).map { warningText(codes!!.getString(it)) }
            return AnalysisResult(measurements, warnings.distinct())
        }
    }
}

/** User-facing text for backend warning codes. */
fun warningText(code: String): String = when (code) {
    "loose_clothing" -> "헐렁한 옷을 입은 사진이라 둘레 치수의 정확도가 낮아요. 속옷이나 몸에 붙는 옷을 입고 다시 찍으면 더 정확해져요."
    "no_weight" -> "몸무게를 입력하면 둘레 치수가 더 정확해져요."
    "arms_touching_body" -> "팔이 몸에 붙어 있어 일부 팔 치수를 재지 못했어요. 팔을 몸에서 조금 더 떼고 찍어주세요."
    "crotch_not_found" -> "다리 사이가 잘 보이지 않아 다리 길이가 부정확할 수 있어요. 다리를 조금 벌리고 찍어주세요."
    else -> "일부 치수의 정확도가 낮을 수 있어요."
}
