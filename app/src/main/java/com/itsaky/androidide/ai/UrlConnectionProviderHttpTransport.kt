package com.itsaky.androidide.ai

import com.itsaky.androidide.ai.providers.ProviderEndpointPolicy
import com.itsaky.androidide.ai.providers.ProviderHttpRequest
import com.itsaky.androidide.ai.providers.ProviderHttpResponse
import com.itsaky.androidide.ai.providers.ProviderHttpTransport
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class UrlConnectionProviderHttpTransport : ProviderHttpTransport {
	private val cancelled = AtomicBoolean(false)
	private val activeConnection = AtomicReference<HttpURLConnection?>()

	override fun execute(request: ProviderHttpRequest): ProviderHttpResponse {
		check(!cancelled.get()) { "Request was cancelled" }
		val url = URL(request.url)
		require(ProviderEndpointPolicy.isAllowed(url)) { "Untrusted AI provider endpoint" }
		require(request.method == "GET" || request.method == "POST") { "Unsupported AI provider request method" }

		val connection = url.openConnection() as HttpURLConnection
		check(activeConnection.compareAndSet(null, connection)) { "Transport already has an active request" }
		try {
			check(!cancelled.get()) { "Request was cancelled" }
			connection.requestMethod = request.method
			connection.connectTimeout = CONNECT_TIMEOUT_MS
			connection.readTimeout = READ_TIMEOUT_MS
			connection.useCaches = false
			connection.instanceFollowRedirects = false
			connection.doInput = true
			request.headers.forEach(connection::setRequestProperty)
			request.body?.let { body ->
				connection.doOutput = true
				val bytes = body.toByteArray(StandardCharsets.UTF_8)
				connection.setFixedLengthStreamingMode(bytes.size)
				connection.outputStream.use { output ->
					output.write(bytes)
				}
			}

			val status = connection.responseCode
			val input =
				if (status in 200..299) {
					connection.inputStream
				} else {
					connection.errorStream ?: return ProviderHttpResponse(status, "")
				}
			input.use { stream ->
				val (bytes, truncated) = readBounded(stream)
				return ProviderHttpResponse(
					statusCode = status,
					body = String(bytes, StandardCharsets.UTF_8),
					isTruncated = truncated,
				)
			}
		} finally {
			activeConnection.compareAndSet(connection, null)
			connection.disconnect()
		}
	}

	override fun cancel() {
		cancelled.set(true)
		activeConnection.getAndSet(null)?.disconnect()
	}

	private fun readBounded(input: java.io.InputStream): Pair<ByteArray, Boolean> {
		val output = ByteArrayOutputStream()
		val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
		var total = 0
		while (true) {
			val count = input.read(buffer)
			if (count < 0) break
			val remaining = MAX_RESPONSE_BYTES - total
			if (count > remaining) {
				if (remaining > 0) output.write(buffer, 0, remaining)
				return output.toByteArray() to true
			}
			output.write(buffer, 0, count)
			total += count
		}
		return output.toByteArray() to false
	}

	private companion object {
		const val CONNECT_TIMEOUT_MS = 15_000
		const val READ_TIMEOUT_MS = 30_000
		const val MAX_RESPONSE_BYTES = 1_000_000
	}
}
