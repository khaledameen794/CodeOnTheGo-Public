package com.itsaky.androidide.ai.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderClientTest {
	private val apiKey = "secret-api-key-123"

	@Test
	fun listsOnlyGeminiModelsThatSupportGenerateContentAndUsesHeaderAuthentication() {
		val requests = mutableListOf<ProviderHttpRequest>()
		val client =
			AiProviderClient { request ->
				requests += request
				ProviderHttpResponse(
					200,
					"""{"models":[{"name":"models/gemini-flash","displayName":"Gemini Flash","supportedGenerationMethods":["generateContent"]},{"name":"models/embed-text","supportedGenerationMethods":["embedContent"]}]}""",
				)
			}

		val result = client.listModels(AiProvider.GEMINI, apiKey)

		assertEquals(
			ProviderResult.Success(listOf(AiProviderModel("gemini-flash", "Gemini Flash"))),
			result,
		)
		val request = requests.single()
		assertEquals("GET", request.method)
		assertTrue(request.url.startsWith("https://generativelanguage.googleapis.com/v1beta/models"))
		assertEquals(apiKey, request.headers["x-goog-api-key"])
		assertFalse(request.url.contains(apiKey))
		assertFalse(request.toString().contains(apiKey))
	}

	@Test
	fun followsGeminiModelPagesWithoutPuttingTheKeyInTheUrl() {
		val requests = mutableListOf<ProviderHttpRequest>()
		val client =
			AiProviderClient { request ->
				requests += request
				if (request.url.contains("pageToken=")) {
					ProviderHttpResponse(
						200,
						"""{"models":[{"name":"models/gemini-second","supportedGenerationMethods":["generateContent"]}]}""",
					)
				} else {
					ProviderHttpResponse(
						200,
						"""{"models":[{"name":"models/gemini-first","supportedGenerationMethods":["generateContent"]}],"nextPageToken":"next page"}""",
					)
				}
			}

		val result = client.listModels(AiProvider.GEMINI, apiKey)

		assertTrue(result is ProviderResult.Success)
		assertEquals(2, (result as ProviderResult.Success).value.size)
		assertEquals(2, requests.size)
		assertTrue(requests.last().url.contains("pageToken=next+page"))
		assertTrue(requests.all { !it.url.contains(apiKey) })
	}

	@Test
	fun listsDeepSeekModelsWithBearerAuthentication() {
		var captured: ProviderHttpRequest? = null
		val client =
			AiProviderClient { request ->
				captured = request
				ProviderHttpResponse(200, """{"data":[{"id":"deepseek-chat"},{"id":"deepseek-reasoner"}]}""")
			}

		val result = client.listModels(AiProvider.DEEPSEEK, apiKey)

		assertTrue(result is ProviderResult.Success)
		assertEquals(
			listOf("deepseek-chat", "deepseek-reasoner"),
			(result as ProviderResult.Success).value.map(AiProviderModel::id),
		)
		assertEquals("Bearer $apiKey", captured?.headers?.get("Authorization"))
		assertEquals("https://api.deepseek.com/models", captured?.url)
		assertFalse(captured!!.url.contains(apiKey))
	}

	@Test
	fun sendsGeminiPromptToDocumentedGenerateContentEndpoint() {
		var captured: ProviderHttpRequest? = null
		val client =
			AiProviderClient { request ->
				captured = request
				ProviderHttpResponse(
					200,
					"""{"candidates":[{"content":{"parts":[{"text":"Hello"},{"text":"world"}]}}]}""",
				)
			}

		val result = client.sendPrompt(AiProvider.GEMINI, apiKey, "gemini-flash", "Say hello")

		assertEquals(ProviderResult.Success(AiProviderReply("Hello\nworld")), result)
		val request = captured!!
		assertEquals(
			"https://generativelanguage.googleapis.com/v1beta/models/gemini-flash:generateContent",
			request.url,
		)
		assertEquals("POST", request.method)
		assertEquals(apiKey, request.headers["x-goog-api-key"])
		assertTrue(request.body.orEmpty().contains("Say hello"))
		assertFalse(request.body.orEmpty().contains(apiKey))
		assertFalse(request.url.contains(apiKey))
		assertFalse(request.toString().contains(apiKey))
	}

	@Test
	fun sendsSingleUserMessageToDeepSeekAndParsesTextResponse() {
		var captured: ProviderHttpRequest? = null
		val client =
			AiProviderClient { request ->
				captured = request
				ProviderHttpResponse(
					200,
					"""{"choices":[{"message":{"role":"assistant","content":"OK"}}]}""",
				)
			}

		val result = client.sendPrompt(AiProvider.DEEPSEEK, apiKey, "deepseek-chat", "Reply OK")

		assertEquals(ProviderResult.Success(AiProviderReply("OK")), result)
		val request = captured!!
		assertEquals("https://api.deepseek.com/chat/completions", request.url)
		assertEquals("Bearer $apiKey", request.headers["Authorization"])
		assertTrue(request.body.orEmpty().contains("Reply OK"))
		assertFalse(request.body.orEmpty().contains(apiKey))
	}

	@Test
	fun rejectsProviderToolCallsInsteadOfExecutingThem() {
		val client =
			AiProviderClient {
				ProviderHttpResponse(
					200,
					"""{"choices":[{"message":{"content":null,"tool_calls":[{"id":"x"}]}}]}""",
				)
			}

		val result = client.sendPrompt(AiProvider.DEEPSEEK, apiKey, "deepseek-chat", "Do something")

		assertEquals(
			ProviderErrorCode.UNSUPPORTED_RESPONSE,
			(result as ProviderResult.Failure).code,
		)
	}

	@Test
	fun malformedNestedProviderObjectsReturnSafeErrors() {
		val malformedGemini =
			AiProviderClient {
				ProviderHttpResponse(200, """{"candidates":[{"content":"not-an-object"}]}""")
			}.sendPrompt(AiProvider.GEMINI, apiKey, "gemini-flash", "Hello")
		val malformedDeepSeek =
			AiProviderClient {
				ProviderHttpResponse(200, """{"choices":[{"message":"not-an-object"}]}""")
			}.sendPrompt(AiProvider.DEEPSEEK, apiKey, "deepseek-chat", "Hello")

		assertEquals(ProviderErrorCode.MALFORMED_RESPONSE, (malformedGemini as ProviderResult.Failure).code)
		assertEquals(ProviderErrorCode.MALFORMED_RESPONSE, (malformedDeepSeek as ProviderResult.Failure).code)
	}

	@Test
	fun mapsProviderErrorsToSafeMessagesWithoutReturningTheResponseBody() {
		val client =
			AiProviderClient {
				ProviderHttpResponse(401, "server reflected $apiKey and must not be shown")
			}

		val result = client.listModels(AiProvider.DEEPSEEK, apiKey) as ProviderResult.Failure

		assertEquals(ProviderErrorCode.INVALID_API_KEY, result.code)
		assertFalse(result.message.contains(apiKey))
		assertFalse(result.message.contains("server reflected"))
	}

	@Test
	fun refusesInvalidInputBeforeMakingANetworkRequest() {
		var calls = 0
		val client =
			AiProviderClient {
				calls++
				ProviderHttpResponse(200, "{}")
			}

		val noKey = client.listModels(AiProvider.GEMINI, " ")
		val badModel = client.sendPrompt(AiProvider.GEMINI, apiKey, "../model", "Hello")
		val hugePrompt = client.sendPrompt(AiProvider.DEEPSEEK, apiKey, "deepseek-chat", "x".repeat(64_001))

		assertEquals(ProviderErrorCode.MISSING_API_KEY, (noKey as ProviderResult.Failure).code)
		assertEquals(ProviderErrorCode.INVALID_MODEL, (badModel as ProviderResult.Failure).code)
		assertEquals(ProviderErrorCode.PROMPT_TOO_LARGE, (hugePrompt as ProviderResult.Failure).code)
		assertEquals(0, calls)
	}

	@Test
	fun hidesTransportExceptionDetails() {
		val client =
			AiProviderClient {
				throw IllegalStateException("failure includes $apiKey")
			}

		val result = client.listModels(AiProvider.GEMINI, apiKey) as ProviderResult.Failure

		assertEquals(ProviderErrorCode.NETWORK_ERROR, result.code)
		assertFalse(result.message.contains(apiKey))
	}
}
