package com.itsaky.androidide.preferences

import android.content.Context
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.Toast
import androidx.preference.Preference
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.R
import com.itsaky.androidide.ai.AiProviderSettingsRepository
import com.itsaky.androidide.ai.AndroidAiProviderCredentials
import com.itsaky.androidide.ai.UrlConnectionProviderHttpTransport
import com.itsaky.androidide.ai.providers.AiProvider
import com.itsaky.androidide.ai.providers.AiProviderClient
import com.itsaky.androidide.ai.providers.ProviderErrorCode
import com.itsaky.androidide.ai.providers.ProviderResult
import com.itsaky.androidide.utils.DialogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize

@Parcelize
class AiProviderPreferencesScreen(
	override val key: String = "idepref_ai_providers",
	override val title: Int = R.string.ai_providers_title,
	override val summary: Int? = R.string.ai_providers_summary,
	override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {
	init {
		addPreference(AiProviderPrivacyNotice())
		addPreference(AiProviderSecurityNotice())
		addPreference(AiProviderSettingsGroup(AiProvider.GEMINI))
		addPreference(AiProviderSettingsGroup(AiProvider.DEEPSEEK))
	}
}

@Parcelize
private class AiProviderPrivacyNotice(
	override val key: String = "ai_provider_privacy_notice",
	override val title: Int = R.string.ai_provider_privacy_title,
	override val summary: Int? = R.string.ai_providers_privacy_summary,
) : SimplePreference()

@Parcelize
private class AiProviderSecurityNotice(
	override val key: String = "ai_provider_security_notice",
	override val title: Int = R.string.ai_provider_security_title,
	override val summary: Int? = R.string.ai_provider_security_summary,
) : SimplePreference()

@Parcelize
private class AiProviderSettingsGroup(
	private val provider: AiProvider,
	override val key: String = "ai_provider_${provider.name.lowercase()}",
	override val title: Int =
		if (provider == AiProvider.GEMINI) {
			R.string.ai_provider_gemini_title
		} else {
			R.string.ai_provider_deepseek_title
		},
	override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {
	init {
		addPreference(AiProviderApiKeyPreference(provider))
		addPreference(AiProviderModelPreference(provider))
		addPreference(AiProviderTestPreference(provider))
		addPreference(AiProviderRemoveKeyPreference(provider))
	}
}

@Parcelize
private class AiProviderApiKeyPreference(
	private val provider: AiProvider,
	override val key: String = apiKeyPreferenceKey(provider),
	override val title: Int = R.string.ai_provider_api_key_title,
) : EditTextPreference() {
	override fun onCreateView(context: Context): Preference =
		super.onCreateView(context).also { preference ->
			preference.summary =
				if (AndroidAiProviderCredentials(context).get(provider) == null) {
					context.getString(R.string.ai_provider_api_key_missing_summary)
				} else {
					context.getString(R.string.ai_provider_api_key_saved_summary)
				}
		}

	override fun onConfigureTextInput(input: TextInputLayout) {
		input.hint = input.context.getString(R.string.ai_provider_api_key_hint)
		input.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
		input.editText?.apply {
			setText("")
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
			transformationMethod = PasswordTransformationMethod.getInstance()
		}
	}

	override fun onPreferenceChanged(
		preference: Preference,
		newValue: Any?,
	): Boolean {
		val secret = newValue as? String ?: return false
		when (AndroidAiProviderCredentials(preference.context).save(provider, secret)) {
			com.itsaky.androidide.ai.providers.CredentialSaveResult.Saved -> {
				AiProviderSettingsRepository(preference.context).clearModels(provider)
				preference.summary = preference.context.getString(R.string.ai_provider_api_key_saved_summary)
				updateModelPreference(preference, provider)
				return true
			}

			com.itsaky.androidide.ai.providers.CredentialSaveResult.EmptySecret -> {
				showToast(preference.context, R.string.ai_provider_secret_empty)
			}

			com.itsaky.androidide.ai.providers.CredentialSaveResult.InvalidSecret -> {
				showToast(preference.context, R.string.ai_provider_secret_invalid)
			}

			com.itsaky.androidide.ai.providers.CredentialSaveResult.StorageFailure -> {
				showToast(preference.context, R.string.ai_provider_secret_save_failed)
			}
		}
		return false
	}
}

@Parcelize
private class AiProviderModelPreference(
	private val provider: AiProvider,
	override val key: String = modelPreferenceKey(provider),
	override val title: Int = R.string.ai_provider_model_title,
) : SingleChoicePreference() {
	override fun onCreateView(context: Context): Preference = super.onCreateView(context).also { updateSummary(it) }

	override fun getEntries(preference: Preference): Array<PreferenceChoices.Entry> {
		val settings = AiProviderSettingsRepository(preference.context)
		val selectedId = settings.selectedModel(provider)
		return settings
			.models(provider)
			.map { model ->
				PreferenceChoices.Entry(
					label = model.displayName,
					_isChecked = model.id == selectedId,
					data = model.id,
				)
			}.toTypedArray()
	}

	override fun onCreatePreference(context: Context): Preference =
		super.onCreatePreference(context).also { preference ->
			preference.isEnabled = AiProviderSettingsRepository(context).models(provider).isNotEmpty()
		}

	override fun onChoiceConfirmed(
		preference: Preference,
		entry: PreferenceChoices.Entry?,
		position: Int,
	) {
		val modelId = entry?.data as? String ?: return
		if (AiProviderSettingsRepository(preference.context).saveSelectedModel(provider, modelId)) {
			updateSummary(preference)
		}
	}

	private fun updateSummary(preference: Preference) {
		val settings = AiProviderSettingsRepository(preference.context)
		val models = settings.models(provider)
		val selected = settings.selectedModel(provider)
		val displayName = models.firstOrNull { it.id == selected }?.displayName
		preference.summary =
			if (displayName == null) {
				preference.context.getString(
					if (models.isEmpty()) {
						R.string.ai_provider_model_not_loaded_summary
					} else {
						R.string.ai_provider_model_not_selected_summary
					},
				)
			} else {
				preference.context.getString(R.string.ai_provider_model_selected_summary, displayName)
			}
		preference.isEnabled = models.isNotEmpty()
	}
}

@Parcelize
private class AiProviderTestPreference(
	private val provider: AiProvider,
	override val key: String = testPreferenceKey(provider),
	override val title: Int = R.string.ai_provider_test_key_title,
	override val summary: Int? = R.string.ai_provider_test_key_summary,
) : SimplePreference() {
	override fun onPreferenceClick(preference: Preference): Boolean {
		val context = preference.context.applicationContext
		val apiKey = AndroidAiProviderCredentials(context).get(provider)
		if (apiKey == null) {
			showToast(context, R.string.ai_provider_key_missing)
			return true
		}

		preference.isEnabled = false
		preference.summary = context.getString(R.string.ai_provider_checking_summary)
		CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
			val result = AiProviderClient(UrlConnectionProviderHttpTransport()).listModels(provider, apiKey)
			if (result is ProviderResult.Success) {
				AiProviderSettingsRepository(context).saveModels(provider, result.value)
			}
			withContext(Dispatchers.Main) {
				preference.isEnabled = true
				when (result) {
					is ProviderResult.Success -> {
						preference.summary =
							if (result.value.isEmpty()) {
								context.getString(R.string.ai_provider_no_models_summary)
							} else {
								context.getString(R.string.ai_provider_verified_models_summary, result.value.size)
							}
						updateModelPreference(preference, provider)
						showToast(
							context,
							if (result.value.isEmpty()) {
								R.string.ai_provider_no_models_summary
							} else {
								R.string.ai_provider_test_succeeded
							},
						)
					}

					is ProviderResult.Failure -> {
						preference.summary = errorMessage(context, result.code)
						showToast(context, errorMessage(context, result.code))
					}
				}
			}
		}
		return true
	}
}

@Parcelize
private class AiProviderRemoveKeyPreference(
	private val provider: AiProvider,
	override val key: String = removePreferenceKey(provider),
	override val title: Int = R.string.ai_provider_remove_key_title,
	override val summary: Int? = R.string.ai_provider_remove_key_summary,
) : SimplePreference() {
	override fun onCreateView(context: Context): Preference =
		super.onCreateView(context).also {
			it.isEnabled = AndroidAiProviderCredentials(context).get(provider) != null
		}

	override fun onPreferenceClick(preference: Preference): Boolean {
		val context = preference.context
		DialogUtils
			.newMaterialDialogBuilder(context)
			.setTitle(R.string.ai_provider_remove_key_title)
			.setMessage(context.getString(R.string.ai_provider_remove_key_confirmation, provider.displayName))
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				if (!AndroidAiProviderCredentials(context).remove(provider)) {
					showToast(context, R.string.ai_provider_secret_remove_failed)
					return@setPositiveButton
				}
				AiProviderSettingsRepository(context).clearModels(provider)
				preference.isEnabled = false
				preference.parent?.findPreference<Preference>(apiKeyPreferenceKey(provider))?.summary =
					context.getString(R.string.ai_provider_api_key_missing_summary)
				updateModelPreference(preference, provider)
				preference.parent?.findPreference<Preference>(testPreferenceKey(provider))?.summary =
					context.getString(R.string.ai_provider_test_key_summary)
				showToast(context, R.string.ai_provider_key_removed)
			}.show()
		return true
	}
}

private fun updateModelPreference(
	preference: Preference,
	provider: AiProvider,
) {
	val modelPreference = preference.parent?.findPreference<Preference>(modelPreferenceKey(provider)) ?: return
	val settings = AiProviderSettingsRepository(preference.context)
	val models = settings.models(provider)
	val selectedId = settings.selectedModel(provider)
	val displayName = models.firstOrNull { it.id == selectedId }?.displayName
	modelPreference.isEnabled = models.isNotEmpty()
	modelPreference.summary =
		if (displayName == null) {
			preference.context.getString(
				if (models.isEmpty()) {
					R.string.ai_provider_model_not_loaded_summary
				} else {
					R.string.ai_provider_model_not_selected_summary
				},
			)
		} else {
			preference.context.getString(R.string.ai_provider_model_selected_summary, displayName)
		}
}

private fun errorMessage(
	context: Context,
	code: ProviderErrorCode,
): String =
	context.getString(
		when (code) {
			ProviderErrorCode.MISSING_API_KEY -> R.string.ai_provider_key_missing
			ProviderErrorCode.INVALID_API_KEY -> R.string.ai_provider_error_invalid_key
			ProviderErrorCode.RATE_LIMITED -> R.string.ai_provider_error_rate_limited
			ProviderErrorCode.NETWORK_ERROR -> R.string.ai_provider_error_network
			ProviderErrorCode.PROVIDER_UNAVAILABLE -> R.string.ai_provider_error_unavailable
			ProviderErrorCode.INVALID_REQUEST, ProviderErrorCode.MODEL_NOT_FOUND -> R.string.ai_provider_error_request
			ProviderErrorCode.MALFORMED_RESPONSE, ProviderErrorCode.RESPONSE_TOO_LARGE -> R.string.ai_provider_error_response
			else -> R.string.ai_provider_error_generic
		},
	)

private fun showToast(
	context: Context,
	message: Int,
) {
	Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

private fun showToast(
	context: Context,
	message: CharSequence,
) {
	Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

private fun apiKeyPreferenceKey(provider: AiProvider) = "ai_provider_api_key_${provider.name.lowercase()}"

private fun modelPreferenceKey(provider: AiProvider) = "ai_provider_model_${provider.name.lowercase()}"

private fun testPreferenceKey(provider: AiProvider) = "ai_provider_test_${provider.name.lowercase()}"

private fun removePreferenceKey(provider: AiProvider) = "ai_provider_remove_${provider.name.lowercase()}"
