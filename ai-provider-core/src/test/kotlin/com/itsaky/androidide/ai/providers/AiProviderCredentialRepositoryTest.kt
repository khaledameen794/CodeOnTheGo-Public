package com.itsaky.androidide.ai.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderCredentialRepositoryTest {
	private val backing = MemorySecretStore()
	private val repository = AiProviderCredentialRepository(backing, TestCipher())

	@Test
	fun savesOnlyCiphertextAndReadsTheSecretBack() {
		val secret = "provider-secret-value"

		assertEquals(CredentialSaveResult.Saved, repository.save(AiProvider.GEMINI, "  $secret  "))

		val stored = backing.values.getValue("gemini")
		assertFalse(stored.ciphertext.decodeToString().contains(secret))
		assertEquals(secret, repository.get(AiProvider.GEMINI))
	}

	@Test
	fun providerKeysAreStoredSeparately() {
		repository.save(AiProvider.GEMINI, "gemini-secret")
		repository.save(AiProvider.DEEPSEEK, "deepseek-secret")

		assertEquals("gemini-secret", repository.get(AiProvider.GEMINI))
		assertEquals("deepseek-secret", repository.get(AiProvider.DEEPSEEK))
		assertEquals(setOf("gemini", "deepseek"), backing.values.keys)
	}

	@Test
	fun rejectsBlankOversizedAndControlCharacterSecretsWithoutWriting() {
		assertEquals(CredentialSaveResult.EmptySecret, repository.save(AiProvider.GEMINI, "  "))
		assertEquals(CredentialSaveResult.InvalidSecret, repository.save(AiProvider.GEMINI, "x".repeat(513)))
		assertEquals(CredentialSaveResult.InvalidSecret, repository.save(AiProvider.GEMINI, "key\nwith-line-break"))
		assertTrue(backing.values.isEmpty())
	}

	@Test
	fun removalOnlyDeletesTheSelectedProviderKey() {
		repository.save(AiProvider.GEMINI, "gemini-secret")
		repository.save(AiProvider.DEEPSEEK, "deepseek-secret")

		repository.remove(AiProvider.GEMINI)

		assertNull(repository.get(AiProvider.GEMINI))
		assertEquals("deepseek-secret", repository.get(AiProvider.DEEPSEEK))
	}

	@Test
	fun corruptCiphertextFailsClosed() {
		val brokenStore =
			object : EncryptedSecretStore {
				override fun read(key: String) = EncryptedSecret(byteArrayOf(), byteArrayOf())

				override fun write(
					key: String,
					value: EncryptedSecret,
				) = Unit

				override fun remove(key: String) = Unit
			}
		val brokenRepository = AiProviderCredentialRepository(brokenStore, TestCipher(throwOnDecrypt = true))

		assertNull(brokenRepository.get(AiProvider.GEMINI))
	}

	@Test
	fun reportsStorageAndRemovalFailuresInsteadOfClaimingSuccess() {
		val failingStore =
			object : EncryptedSecretStore {
				override fun read(key: String): EncryptedSecret? = null

				override fun write(
					key: String,
					value: EncryptedSecret,
				) = error("commit failed")

				override fun remove(key: String) = error("commit failed")
			}
		val failingRepository = AiProviderCredentialRepository(failingStore, TestCipher())

		assertEquals(CredentialSaveResult.StorageFailure, failingRepository.save(AiProvider.GEMINI, "secret"))
		assertFalse(failingRepository.remove(AiProvider.GEMINI))
	}

	private class MemorySecretStore : EncryptedSecretStore {
		val values = mutableMapOf<String, EncryptedSecret>()

		override fun read(key: String): EncryptedSecret? = values[key]

		override fun write(
			key: String,
			value: EncryptedSecret,
		) {
			values[key] = value
		}

		override fun remove(key: String) {
			values.remove(key)
		}
	}

	private class TestCipher(
		private val throwOnDecrypt: Boolean = false,
	) : ReversibleStringCipher {
		override fun encrypt(value: String): EncryptedSecret = EncryptedSecret(byteArrayOf(1, 2, 3), value.reversed().toByteArray())

		override fun decrypt(value: EncryptedSecret): String {
			if (throwOnDecrypt) error("corrupt")
			return value.ciphertext.decodeToString().reversed()
		}
	}
}
