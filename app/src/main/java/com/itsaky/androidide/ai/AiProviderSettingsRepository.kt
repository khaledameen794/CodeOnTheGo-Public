package com.itsaky.androidide.ai

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.itsaky.androidide.ai.providers.AiProvider
import com.itsaky.androidide.ai.providers.AiProviderModel

class AiProviderSettingsRepository(
	context: Context,
) {
	private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
	private val gson = Gson()

	fun models(provider: AiProvider): List<AiProviderModel> =
		synchronized(lock) {
			val serialized = preferences.getString(modelsKey(provider), null) ?: return@synchronized emptyList()
			runCatching {
				gson.fromJson<List<AiProviderModel>>(serialized, MODEL_LIST_TYPE).orEmpty()
			}.getOrDefault(emptyList())
		}

	fun saveModels(
		provider: AiProvider,
		models: List<AiProviderModel>,
	) = synchronized(lock) {
		val validModels = models.distinctBy(AiProviderModel::id)
		preferences
			.edit()
			.putString(modelsKey(provider), gson.toJson(validModels))
			.apply()
		val selected = preferences.getString(selectedModelKey(provider), null)
		if (selected != null && validModels.none { it.id == selected }) {
			preferences.edit().remove(selectedModelKey(provider)).apply()
		}
	}

	fun clearModels(provider: AiProvider) =
		synchronized(lock) {
			preferences
				.edit()
				.remove(modelsKey(provider))
				.remove(selectedModelKey(provider))
				.apply()
		}

	fun selectedModel(provider: AiProvider): String? =
		synchronized(lock) {
			preferences.getString(selectedModelKey(provider), null)
		}

	fun saveSelectedModel(
		provider: AiProvider,
		modelId: String,
	): Boolean =
		synchronized(lock) {
			if (models(provider).none { it.id == modelId }) return@synchronized false
			preferences.edit().putString(selectedModelKey(provider), modelId).apply()
			true
		}

	private fun modelsKey(provider: AiProvider) = "models.${provider.name.lowercase()}"

	private fun selectedModelKey(provider: AiProvider) = "selected_model.${provider.name.lowercase()}"

	private companion object {
		const val PREFS_NAME = "ai_provider_settings_v1"
		val MODEL_LIST_TYPE = object : TypeToken<List<AiProviderModel>>() {}.type
		val lock = Any()
	}
}
