package com.itsaky.androidide.ai

import android.content.Context
import android.util.Base64
import com.itsaky.androidide.ai.providers.AiProviderCredentialRepository
import com.itsaky.androidide.ai.providers.EncryptedSecret
import com.itsaky.androidide.ai.providers.EncryptedSecretStore
import com.itsaky.androidide.ai.providers.ReversibleStringCipher
import com.itsaky.androidide.git.core.CryptoManager

class AndroidAiProviderCredentials(
	context: Context,
) {
	private val store =
		SharedPreferencesAiSecretStore(
			context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
		)
	private val cipher = AndroidAiSecretCipher
	private val repository = AiProviderCredentialRepository(store, cipher)

	fun save(
		provider: com.itsaky.androidide.ai.providers.AiProvider,
		secret: String,
	) = repository.save(provider, secret)

	fun get(provider: com.itsaky.androidide.ai.providers.AiProvider): String? = repository.get(provider)

	fun remove(provider: com.itsaky.androidide.ai.providers.AiProvider) = repository.remove(provider)

	private companion object {
		const val PREFS_NAME = "ai_provider_credentials_v1"
	}
}

private class SharedPreferencesAiSecretStore(
	private val preferences: android.content.SharedPreferences,
) : EncryptedSecretStore {
	override fun read(key: String): EncryptedSecret? {
		val iv = preferences.getString("$key.iv", null) ?: return null
		val ciphertext = preferences.getString("$key.data", null) ?: return null
		return EncryptedSecret(
			iv = Base64.decode(iv, Base64.NO_WRAP),
			ciphertext = Base64.decode(ciphertext, Base64.NO_WRAP),
		)
	}

	override fun write(
		key: String,
		value: EncryptedSecret,
	) {
		val committed =
			preferences
				.edit()
				.putString("$key.iv", Base64.encodeToString(value.iv, Base64.NO_WRAP))
				.putString("$key.data", Base64.encodeToString(value.ciphertext, Base64.NO_WRAP))
				.commit()
		check(committed) {
			"Encrypted AI credential could not be committed"
		}
	}

	override fun remove(key: String) {
		val committed =
			preferences
				.edit()
				.remove("$key.iv")
				.remove("$key.data")
				.commit()
		check(committed) {
			"Encrypted AI credential removal could not be committed"
		}
	}
}

private object AndroidAiSecretCipher : ReversibleStringCipher {
	override fun encrypt(value: String): EncryptedSecret {
		val (iv, ciphertext) = CryptoManager.encrypt(value, KEY_ALIAS)
		return EncryptedSecret(iv, ciphertext)
	}

	override fun decrypt(value: EncryptedSecret): String = CryptoManager.decrypt(value.iv, value.ciphertext, KEY_ALIAS)

	private const val KEY_ALIAS = "ai_provider_api_key_v1"
}
