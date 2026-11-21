package com.itsaky.androidide.ai.providers

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val MAX_RESPONSE_CHARS = 1_000_000
private const val MAX_PROMPT_CHARS = 64_000
private const val MAX_MODEL_PAGES = 10

/** Only the documented text-generation operations are implemented; model tool calls are never run. */
class AiProviderClient(
	private val transport: ProviderHttpTransport,
) {
	fun listModels(
		provider: AiProvider,
		apiKey: String,
	): ProviderResult<List<AiProviderModel>> {
		val credentialError = validateApiKey(apiKey)
		if (credentialError != null) return credentialError

		return when (provider) {
			AiProvider.GEMINI -> listGeminiModels(apiKey)
			AiProvider.DEEPSEEK -> listDeepSeekModels(apiKey)
		}
	}

	fun sendPrompt(
		provider: AiProvider,
		apiKey: String,
		modelId: String,
		prompt: String,
	): ProviderResult<AiProviderReply> {
		val credentialError = validateApiKey(apiKey)
		if (credentialError != null) return credentialError
		if (!MODEL_ID.matches(modelId)) {
			return failure(ProviderErrorCode.INVALID_MODEL)
		}
		if (prompt.isBlank()) return failure(ProviderErrorCode.EMPTY_PROMPT)
		if (prompt.length > MAX_PROMPT_CHARS) return failure(ProviderErrorCode.PROMPT_TOO_LARGE)

		val request =
			when (provider) {
				AiProvider.GEMINI -> geminiGenerateRequest(apiKey, modelId, prompt)
				AiProvider.DEEPSEEK -> deepSeekGenerateRequest(apiKey, modelId, prompt)
			}
		val json = requestJson(request)
		if (json is ProviderResult.Failure) return json
		json as ProviderResult.Success

		return when (provider) {
			AiProvider.GEMINI -> parseGeminiReply(json.value)
			AiProvider.DEEPSEEK -> parseDeepSeekReply(json.value)
		}
	}

	private fun listGeminiModels(apiKey: String): ProviderResult<List<AiProviderModel>> {
		val models = linkedMapOf<String, AiProviderModel>()
		val seenTokens = mutableSetOf<String>()
		var pageToken: String? = null
		repeat(MAX_MODEL_PAGES) {
			val query =
				buildString {
					append("?pageSize=1000")
					pageToken?.let {
						append("&pageToken=")
						append(URLEncoder.encode(it, StandardCharsets.UTF_8.name()))
					}
				}
			val response =
				requestJson(
					ProviderHttpRequest(
						method = "GET",
						url = AiProvider.GEMINI.baseUrl + "/v1beta/models" + query,
						headers = geminiHeaders(apiKey),
					),
				)
			if (response is ProviderResult.Failure) return response
			val root = (response as ProviderResult.Success).value
			val entries =
				root.arrayOrNull("models")
					?: return failure(ProviderErrorCode.MALFORMED_RESPONSE)
			entries.forEach { entry ->
				if (!entry.isJsonObject) return@forEach
				val model = entry.asJsonObject
				val rawName = model.stringOrNull("name") ?: return@forEach
				val id = rawName.removePrefix("models/")
				if (!MODEL_ID.matches(id)) return@forEach
				val methods = model.arrayOrNull("supportedGenerationMethods")
				if (methods?.any { it.stringOrNull() == "generateContent" } != true) return@forEach
				models[id] =
					AiProviderModel(
						id = id,
						displayName = model.stringOrNull("displayName")?.takeIf(String::isNotBlank) ?: id,
					)
			}
			val next = root.stringOrNull("nextPageToken")?.takeIf(String::isNotBlank)
			if (next == null) {
				return success(models.values.sortedBy { it.displayName.lowercase() })
			}
			if (!seenTokens.add(next)) return failure(ProviderErrorCode.MALFORMED_RESPONSE)
			pageToken = next
		}
		return success(models.values.sortedBy { it.displayName.lowercase() })
	}

	private fun listDeepSeekModels(apiKey: String): ProviderResult<List<AiProviderModel>> {
		val response =
			requestJson(
				ProviderHttpRequest(
					method = "GET",
					url = AiProvider.DEEPSEEK.baseUrl + "/models",
					headers = deepSeekHeaders(apiKey),
				),
			)
		if (response is ProviderResult.Failure) return response
		val root = (response as ProviderResult.Success).value
		val entries = root.arrayOrNull("data") ?: return failure(ProviderErrorCode.MALFORMED_RESPONSE)
		val models =
			entries
				.mapNotNull { entry ->
					if (!entry.isJsonObject) return@mapNotNull null
					val model = entry.asJsonObject
					val id = model.stringOrNull("id")?.takeIf(MODEL_ID::matches) ?: return@mapNotNull null
					AiProviderModel(id, model.stringOrNull("name")?.takeIf(String::isNotBlank) ?: id)
				}.distinctBy(AiProviderModel::id)
				.sortedBy { it.displayName.lowercase() }
		return success(models)
	}

	private fun geminiGenerateRequest(
		apiKey: String,
		modelId: String,
		prompt: String,
	): ProviderHttpRequest {
		val part = JsonObject().apply { addProperty("text", prompt) }
		val content = JsonObject().apply { add("parts", JsonArray().apply { add(part) }) }
		val body = JsonObject().apply { add("contents", JsonArray().apply { add(content) }) }
		return ProviderHttpRequest(
			method = "POST",
			url = AiProvider.GEMINI.baseUrl + "/v1beta/models/$modelId:generateContent",
			headers = geminiHeaders(apiKey) + ("Content-Type" to "application/json"),
			body = body.toString(),
		)
	}

	private fun deepSeekGenerateRequest(
		apiKey: String,
		modelId: String,
		prompt: String,
	): ProviderHttpRequest {
		val message =
			JsonObject().apply {
				addProperty("role", "user")
				addProperty("content", prompt)
			}
		val body =
			JsonObject().apply {
				addProperty("model", modelId)
				add("messages", JsonArray().apply { add(message) })
				addProperty("stream", false)
			}
		return ProviderHttpRequest(
			method = "POST",
			url = AiProvider.DEEPSEEK.baseUrl + "/chat/completions",
			headers = deepSeekHeaders(apiKey) + ("Content-Type" to "application/json"),
			body = body.toString(),
		)
	}

	private fun parseGeminiReply(root: JsonObject): ProviderResult<AiProviderReply> {
		val candidates = root.arrayOrNull("candidates")
		val first = candidates?.firstOrNull()?.takeIf(JsonElement::isJsonObject)?.asJsonObject
		val parts = first?.objectOrNull("content")?.arrayOrNull("parts")
		val text =
			parts
				?.mapNotNull { part ->
					part.takeIf(JsonElement::isJsonObject)?.asJsonObject?.stringOrNull("text")
				}?.filter(String::isNotBlank)
				?.joinToString("\n")
				?.takeIf(String::isNotBlank)
		if (text != null) return success(AiProviderReply(text))

		val blockReason = root.objectOrNull("promptFeedback")?.stringOrNull("blockReason")
		if (!blockReason.isNullOrBlank()) return failure(ProviderErrorCode.PROMPT_BLOCKED)
		return failure(ProviderErrorCode.MALFORMED_RESPONSE)
	}

	private fun parseDeepSeekReply(root: JsonObject): ProviderResult<AiProviderReply> {
		val first =
			root
				.arrayOrNull("choices")
				?.firstOrNull()
				?.takeIf(JsonElement::isJsonObject)
				?.asJsonObject
				?: return failure(ProviderErrorCode.MALFORMED_RESPONSE)
		val message =
			first.objectOrNull("message")
				?: return failure(ProviderErrorCode.MALFORMED_RESPONSE)
		val toolCalls = message.arrayOrNull("tool_calls")
		if (toolCalls != null && toolCalls.size() > 0) {
			return failure(ProviderErrorCode.UNSUPPORTED_RESPONSE)
		}
		val text =
			message.stringOrNull("content")?.takeIf(String::isNotBlank)
				?: return failure(ProviderErrorCode.MALFORMED_RESPONSE)
		return success(AiProviderReply(text))
	}

	private fun requestJson(request: ProviderHttpRequest): ProviderResult<JsonObject> {
		val response =
			try {
				transport.execute(request)
			} catch (_: Exception) {
				return failure(ProviderErrorCode.NETWORK_ERROR)
			}
		if (response.isTruncated || response.body.length > MAX_RESPONSE_CHARS) {
			return failure(ProviderErrorCode.RESPONSE_TOO_LARGE)
		}
		if (response.statusCode !in 200..299) {
			return failure(errorForStatus(response.statusCode))
		}
		val parsed =
			try {
				JsonParser.parseString(response.body)
			} catch (_: Exception) {
				return failure(ProviderErrorCode.MALFORMED_RESPONSE)
			}
		if (!parsed.isJsonObject) return failure(ProviderErrorCode.MALFORMED_RESPONSE)
		return success(parsed.asJsonObject)
	}

	private fun validateApiKey(apiKey: String): ProviderResult.Failure? {
		if (apiKey.isBlank()) return failure(ProviderErrorCode.MISSING_API_KEY)
		if (apiKey.length > 512 || apiKey.any(Char::isISOControl)) {
			return failure(ProviderErrorCode.INVALID_API_KEY)
		}
		return null
	}

	private fun geminiHeaders(apiKey: String) = mapOf("x-goog-api-key" to apiKey, "Accept" to "application/json")

	private fun deepSeekHeaders(apiKey: String) = mapOf("Authorization" to "Bearer $apiKey", "Accept" to "application/json")

	private fun errorForStatus(statusCode: Int): ProviderErrorCode =
		when (statusCode) {
			401, 403 -> ProviderErrorCode.INVALID_API_KEY
			404 -> ProviderErrorCode.MODEL_NOT_FOUND
			429 -> ProviderErrorCode.RATE_LIMITED
			400 -> ProviderErrorCode.INVALID_REQUEST
			in 500..599 -> ProviderErrorCode.PROVIDER_UNAVAILABLE
			else -> ProviderErrorCode.HTTP_ERROR
		}

	private fun <T> success(value: T) = ProviderResult.Success(value)

	private fun failure(code: ProviderErrorCode) = ProviderResult.Failure(code, code.userMessage)

	private companion object {
		val MODEL_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
	}
}

data class AiProviderModel(
	val id: String,
	val displayName: String,
)

data class AiProviderReply(
	val text: String,
)

sealed interface ProviderResult<out T> {
	data class Success<T>(
		val value: T,
	) : ProviderResult<T>

	data class Failure(
		val code: ProviderErrorCode,
		val message: String,
	) : ProviderResult<Nothing>
}

enum class ProviderErrorCode(
	val userMessage: String,
) {
	MISSING_API_KEY("Add an API key for this provider first."),
	INVALID_API_KEY("The provider rejected this API key."),
	INVALID_MODEL("The selected model ID is invalid."),
	MODEL_NOT_FOUND("The provider could not find this model."),
	EMPTY_PROMPT("Enter a prompt before sending it."),
	PROMPT_TOO_LARGE("The prompt is too long to send."),
	PROMPT_BLOCKED("The provider blocked this prompt."),
	RATE_LIMITED("The provider rate limit was reached. Try again later."),
	INVALID_REQUEST("The provider rejected the request. Check the selected model and prompt."),
	PROVIDER_UNAVAILABLE("The provider is temporarily unavailable."),
	NETWORK_ERROR("Could not reach the provider. Check the connection and try again."),
	RESPONSE_TOO_LARGE("The provider response exceeded the safe size limit."),
	MALFORMED_RESPONSE("The provider returned an unreadable response."),
	UNSUPPORTED_RESPONSE("The provider returned an unsupported tool-call response."),
	HTTP_ERROR("The provider returned an unexpected error."),
}

private fun JsonObject.arrayOrNull(name: String): JsonArray? = get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray

private fun JsonObject.objectOrNull(name: String): JsonObject? = get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

private fun JsonElement.stringOrNull(): String? = if (isJsonPrimitive && asJsonPrimitive.isString) asString else null

private fun JsonObject.stringOrNull(name: String): String? = get(name)?.stringOrNull()
