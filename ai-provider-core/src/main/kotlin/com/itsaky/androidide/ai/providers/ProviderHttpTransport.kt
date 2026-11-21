package com.itsaky.androidide.ai.providers

import java.net.URL

data class ProviderHttpRequest(
	val method: String,
	val url: String,
	val headers: Map<String, String>,
	val body: String? = null,
) {
	override fun toString(): String =
		"ProviderHttpRequest(method=$method, url=$url, headerNames=${headers.keys}, bodyLength=${body?.length ?: 0})"
}

data class ProviderHttpResponse(
	val statusCode: Int,
	val body: String,
	val isTruncated: Boolean = false,
)

fun interface ProviderHttpTransport {
	fun execute(request: ProviderHttpRequest): ProviderHttpResponse

	/** Disconnects an active request when supported by the platform transport. */
	fun cancel() = Unit
}

object ProviderEndpointPolicy {
	private val allowedHosts = setOf("generativelanguage.googleapis.com", "api.deepseek.com")

	fun isAllowed(url: URL): Boolean =
		url.protocol.equals("https", ignoreCase = true) &&
			allowedHosts.any { it.equals(url.host, ignoreCase = true) } &&
			(url.port == -1 || url.port == HTTPS_PORT) &&
			url.userInfo == null &&
			url.ref == null

	private const val HTTPS_PORT = 443
}
