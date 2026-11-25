package com.truelens.camera.grading

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persists user-created custom Lightroom recipes in filesDir/custom_presets.json.
 */
class CustomPresetRepository(context: Context) {

    private val file = File(context.filesDir, "custom_presets.json")

    suspend fun loadCustomPresets(): List<GradePreset> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            val arr = JSONArray(file.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                GradePreset(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    category = "CUSTOM",
                    subtitle = o.optString("subtitle", "Custom recipe"),
                    grade = LightroomGrade(
                        exposure = o.optDouble("exposure", 0.0).toFloat(),
                        contrast = o.optDouble("contrast", 0.0).toFloat(),
                        highlights = o.optDouble("highlights", 0.0).toFloat(),
                        shadows = o.optDouble("shadows", 0.0).toFloat(),
                        whites = o.optDouble("whites", 0.0).toFloat(),
                        blacks = o.optDouble("blacks", 0.0).toFloat(),
                        temperature = o.optDouble("temperature", 0.0).toFloat(),
                        tint = o.optDouble("tint", 0.0).toFloat(),
                        vibrance = o.optDouble("vibrance", 0.0).toFloat(),
                        saturation = o.optDouble("saturation", 0.0).toFloat(),
                        clarity = o.optDouble("clarity", 0.0).toFloat(),
                        vignette = o.optDouble("vignette", 0.0).toFloat(),
                        grain = o.optDouble("grain", 0.0).toFloat()
                    ),
                    isCustom = true
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun saveCustomPreset(name: String, grade: LightroomGrade): List<GradePreset> =
        withContext(Dispatchers.IO) {
            val current = loadCustomPresets().toMutableList()
            val preset = GradePreset(
                id = "custom_${UUID.randomUUID()}",
                name = name.trim().ifBlank { "My Recipe" },
                category = "CUSTOM",
                subtitle = "Your saved recipe",
                grade = grade,
                isCustom = true
            )
            current.add(0, preset)
            persistSync(current)
            current
        }

    suspend fun deleteCustomPreset(id: String): List<GradePreset> = withContext(Dispatchers.IO) {
        val updated = loadCustomPresets().filterNot { it.id == id }
        persistSync(updated)
        updated
    }

    private fun persistSync(presets: List<GradePreset>) {
        val arr = JSONArray()
        presets.forEach { p ->
            val g = p.grade
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("subtitle", p.subtitle)
                    put("exposure", g.exposure.toDouble())
                    put("contrast", g.contrast.toDouble())
                    put("highlights", g.highlights.toDouble())
                    put("shadows", g.shadows.toDouble())
                    put("whites", g.whites.toDouble())
                    put("blacks", g.blacks.toDouble())
                    put("temperature", g.temperature.toDouble())
                    put("tint", g.tint.toDouble())
                    put("vibrance", g.vibrance.toDouble())
                    put("saturation", g.saturation.toDouble())
                    put("clarity", g.clarity.toDouble())
                    put("vignette", g.vignette.toDouble())
                    put("grain", g.grain.toDouble())
                }
            )
        }
        file.writeText(arr.toString())
    }
}
