package com.swpp.stylemate.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Keeps the confirmed body profile on the device, so it survives app restarts and other features
 * (e.g. outfit recommendation) can read it. Only numbers and preferences are stored, never photos.
 */
interface BodyProfileStore {
    fun load(): BodyProfile?
    fun save(profile: BodyProfile?)
}

class InMemoryBodyProfileStore(private var profile: BodyProfile? = null) : BodyProfileStore {
    override fun load(): BodyProfile? = profile
    override fun save(profile: BodyProfile?) {
        this.profile = profile
    }
}

class SharedPrefsBodyProfileStore(context: Context) : BodyProfileStore {
    private val prefs = context.applicationContext.getSharedPreferences("body_profile", Context.MODE_PRIVATE)

    override fun load(): BodyProfile? = prefs.getString(KEY, null)?.let(BodyProfileJson::decode)

    override fun save(profile: BodyProfile?) {
        prefs.edit().apply {
            if (profile == null) remove(KEY) else putString(KEY, BodyProfileJson.encode(profile))
        }.apply()
    }

    private companion object {
        const val KEY = "profile_v1"
    }
}

/** JSON form of [BodyProfile]. Measurement keys match the API (`MeasurementType.apiKey`). */
object BodyProfileJson {
    fun encode(profile: BodyProfile): String = JSONObject().apply {
        put("height_cm", profile.input.heightCm)
        put("weight_kg", profile.input.weightKg ?: JSONObject.NULL)
        put("gender", profile.input.gender.name.lowercase())
        put("measurements", JSONArray().apply {
            profile.measurements.forEach { m ->
                put(JSONObject().apply {
                    put("type", m.type.apiKey)
                    put("value_cm", m.valueCm)
                    put("confidence", m.confidence.name.lowercase())
                    put("edited_by_user", m.editedByUser)
                })
            }
        })
        put("preferred_fit", profile.preferredFit.name.lowercase())
        put("preferred_styles", JSONArray(profile.preferredStyles))
        put("clothing", JSONObject().apply {
            put("top_loose", profile.clothing.topLoose)
            put("bottom_loose", profile.clothing.bottomLoose)
        })
        profile.reference?.let { ref ->
            put("reference", JSONObject().apply {
                put("body", ref.body)
                put("height_cm", ref.heightCm)
                put("measurements", JSONObject().apply { ref.values.forEach { (t, v) -> put(t.apiKey, v) } })
            })
        }
    }.toString()

    /** Null if [text] is not a valid profile (e.g. written by an incompatible version). */
    fun decode(text: String): BodyProfile? = try {
        val json = JSONObject(text)
        val byKey = MeasurementType.entries.associateBy { it.apiKey }
        val items = json.getJSONArray("measurements")
        val measurements = (0 until items.length()).mapNotNull { i ->
            val m = items.getJSONObject(i)
            val type = byKey[m.getString("type")] ?: return@mapNotNull null
            BodyMeasurement(
                type = type,
                valueCm = m.getDouble("value_cm"),
                confidence = Confidence.valueOf(m.getString("confidence").uppercase()),
                editedByUser = m.optBoolean("edited_by_user"),
            )
        }
        val styles = json.optJSONArray("preferred_styles")
        val clothing = json.optJSONObject("clothing")
        val reference = json.optJSONObject("reference")?.let { ref ->
            val values = ref.getJSONObject("measurements")
            ReferenceMeasurements(
                body = ref.getString("body"),
                heightCm = ref.getDouble("height_cm"),
                values = values.keys().asSequence().mapNotNull { k -> byKey[k]?.let { it to values.getDouble(k) } }.toMap(),
            )
        }
        BodyProfile(
            input = AnalysisInput(
                heightCm = json.getInt("height_cm"),
                weightKg = if (json.isNull("weight_kg")) null else json.getInt("weight_kg"),
                gender = Gender.valueOf(json.getString("gender").uppercase()),
            ),
            measurements = measurements,
            preferredFit = PreferredFit.entries.firstOrNull { it.name.lowercase() == json.optString("preferred_fit") }
                ?: PreferredFit.REGULAR,
            preferredStyles = (0 until (styles?.length() ?: 0)).map { styles!!.getString(it) }.take(MAX_STYLES),
            clothing = DetectedClothing(
                topLoose = clothing?.optBoolean("top_loose") ?: false,
                bottomLoose = clothing?.optBoolean("bottom_loose") ?: false,
            ),
            reference = reference,
        )
    } catch (e: JSONException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
