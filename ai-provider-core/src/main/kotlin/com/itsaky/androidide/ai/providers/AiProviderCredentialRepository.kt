package com.itsaky.androidide.ai.providers

data class EncryptedSecret(
	val iv: ByteArray,
	val ciphertext: ByteArray,
)

fun interface StringCipher {
	fun encrypt(value: String): EncryptedSecret
}

interface ReversibleStringCipher : StringCipher {
	fun decrypt(value: EncryptedSecret): String
}

interface EncryptedSecretStore {
	fun read(key: String): EncryptedSecret?

	fun write(
		key: String,
		value: EncryptedSecret,
	)

	fun remove(key: String)
}

sealed interface CredentialSaveResult {
	data object Saved : CredentialSaveResult

	data object EmptySecret : CredentialSaveResult

	data object InvalidSecret : CredentialSaveResult

	data object StorageFailure : CredentialSaveResult
}

/** Stores only encrypted provider secrets. Provider keys are not shared with Git credentials. */
class AiProviderCredentialRepository(
	private val store: EncryptedSecretStore,
	private val cipher: ReversibleStringCipher,
) {
	fun save(
		provider: AiProvider,
		secret: String,
	): CredentialSaveResult {
		val normalized = secret.trim()
		if (normalized.isEmpty()) return CredentialSaveResult.EmptySecret
		if (normalized.length > MAX_SECRET_LENGTH || normalized.any(Char::isISOControl)) {
			return CredentialSaveResult.InvalidSecret
		}

		return try {
			store.write(provider.credentialKey, cipher.encrypt(normalized))
			CredentialSaveResult.Saved
		} catch (_: Exception) {
			CredentialSaveResult.StorageFailure
		}
	}

	fun get(provider: AiProvider): String? =
		try {
			store.read(provider.credentialKey)?.let(cipher::decrypt)
		} catch (_: Exception) {
			null
		}

	fun remove(provider: AiProvider): Boolean =
		try {
			store.remove(provider.credentialKey)
			true
		} catch (_: Exception) {
			false
		}

	private companion object {
		const val MAX_SECRET_LENGTH = 512
	}
}

enum class AiProvider(
	val displayName: String,
	val baseUrl: String,
) {
	GEMINI("Google Gemini", "https://generativelanguage.googleapis.com"),
	DEEPSEEK("DeepSeek", "https://api.deepseek.com"),
	;

	internal val credentialKey: String
		get() = name.lowercase()
}
