package com.itsaky.androidide.git.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Helper class for encrypting and decrypting data using the Android KeyStore.
 * Uses AES-GCM for strong encryption with authentication.
 */
object CryptoManager {
	private const val ALGORITHM = "AES/GCM/NoPadding"
	private const val ANDROID_KEYSTORE = "AndroidKeyStore"
	private const val KEY_ALIAS = "git_credentials_key"
	private const val GCM_IV_SIZE = 12
	private const val GCM_TAG_SIZE = 16
	private val keyLock = Any()
	private val keyAliasPattern = Regex("^[A-Za-z0-9_.-]{1,128}$")

	private fun getSecretKey(keyAlias: String): SecretKey {
		require(keyAliasPattern.matches(keyAlias)) { "Invalid Android Keystore key alias" }
		return synchronized(keyLock) {
			val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
			(keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
				?: generateSecretKey(keyAlias)
		}
	}

	private fun generateSecretKey(keyAlias: String): SecretKey {
		val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
		val spec =
			KeyGenParameterSpec
				.Builder(
					keyAlias,
					KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
				).setKeySize(256)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setRandomizedEncryptionRequired(true)
				.build()

		keyGenerator.init(spec)
		return keyGenerator.generateKey()
	}

	/** Encrypts [data] and returns the initialization vector and authenticated ciphertext. */
	fun encrypt(data: String): Pair<ByteArray, ByteArray> = encrypt(data, KEY_ALIAS)

	/** Encrypts data with a dedicated Keystore key for the supplied storage domain. */
	fun encrypt(
		data: String,
		keyAlias: String,
	): Pair<ByteArray, ByteArray> {
		val cipher = Cipher.getInstance(ALGORITHM)
		cipher.init(Cipher.ENCRYPT_MODE, getSecretKey(keyAlias))
		val iv = cipher.iv ?: error("Android Keystore did not produce a GCM initialization vector")
		check(iv.size == GCM_IV_SIZE) { "Android Keystore produced an invalid GCM initialization vector" }
		return iv to cipher.doFinal(data.toByteArray(Charsets.UTF_8))
	}

	/** Decrypts [ciphertext] using the supplied initialization vector. */
	fun decrypt(
		iv: ByteArray,
		ciphertext: ByteArray,
	): String = decrypt(iv, ciphertext, KEY_ALIAS)

	/** Decrypts data with the same dedicated Keystore key used to encrypt it. */
	fun decrypt(
		iv: ByteArray,
		ciphertext: ByteArray,
		keyAlias: String,
	): String {
		require(iv.size == GCM_IV_SIZE) { "Invalid AES-GCM initialization vector" }
		require(ciphertext.size >= GCM_TAG_SIZE) { "Invalid AES-GCM ciphertext" }
		val cipher = Cipher.getInstance(ALGORITHM)
		cipher.init(Cipher.DECRYPT_MODE, getSecretKey(keyAlias), GCMParameterSpec(128, iv))
		return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
	}
}
