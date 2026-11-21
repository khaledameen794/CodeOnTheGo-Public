package com.itsaky.androidide.agent.project

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** A bounded suggestion that can only fill fields already supported by a selected template. */
data class AgentProjectSuggestion(
	val projectName: String,
	val packageName: String?,
	val language: String?,
)

sealed interface AgentProjectSuggestionParseResult {
	data class Parsed(
		val suggestion: AgentProjectSuggestion,
	) : AgentProjectSuggestionParseResult

	data object InvalidJson : AgentProjectSuggestionParseResult

	data object InvalidFields : AgentProjectSuggestionParseResult

	data object InvalidValue : AgentProjectSuggestionParseResult

	data object ResponseTooLarge : AgentProjectSuggestionParseResult
}

object AgentProjectSuggestionParser {
	private const val MAX_RESPONSE_CHARS = 8_192
	private val allowedFields = setOf("projectName", "packageName", "language")
	private val fencedJson = Regex("^```(?:json)?\\s*([\\s\\S]*?)\\s*```$", RegexOption.IGNORE_CASE)

	/**
	 * Parses a response only when it contains exactly the fields requested for this template.
	 * Filesystem paths, commands, repository URLs, and other model-provided fields are rejected.
	 */
	fun parse(
		response: String,
		expectedFields: Set<String>,
	): AgentProjectSuggestionParseResult {
		if (response.length > MAX_RESPONSE_CHARS) return AgentProjectSuggestionParseResult.ResponseTooLarge
		if ("projectName" !in expectedFields || !allowedFields.containsAll(expectedFields)) {
			return AgentProjectSuggestionParseResult.InvalidFields
		}

		val content = fencedJson.matchEntire(response.trim())?.groupValues?.get(1) ?: response.trim()
		val parsed =
			runCatching { JsonParser.parseString(content) }.getOrNull()
				?: return AgentProjectSuggestionParseResult.InvalidJson
		if (!parsed.isJsonObject) return AgentProjectSuggestionParseResult.InvalidJson

		val fields = parsed.asJsonObject
		if (fields.keySet() != expectedFields) return AgentProjectSuggestionParseResult.InvalidFields

		val projectName =
			fields.stringValue("projectName", maxLength = 64)
				?: return AgentProjectSuggestionParseResult.InvalidValue
		val packageName =
			if ("packageName" in expectedFields) {
				fields.nullableStringValue("packageName", maxLength = 255)
					?: if (fields.get("packageName")?.isJsonNull == true) null else return AgentProjectSuggestionParseResult.InvalidValue
			} else {
				null
			}
		val language =
			if ("language" in expectedFields) {
				fields.nullableStringValue("language", maxLength = 40)
					?: if (fields.get("language")?.isJsonNull == true) null else return AgentProjectSuggestionParseResult.InvalidValue
			} else {
				null
			}

		return AgentProjectSuggestionParseResult.Parsed(
			AgentProjectSuggestion(projectName, packageName, language),
		)
	}

	private fun JsonObject.stringValue(
		name: String,
		maxLength: Int,
	): String? = get(name)?.stringValue(maxLength)?.takeIf(String::isNotBlank)

	private fun JsonObject.nullableStringValue(
		name: String,
		maxLength: Int,
	): String? = get(name)?.takeUnless(JsonElement::isJsonNull)?.stringValue(maxLength)

	private fun JsonElement.stringValue(maxLength: Int): String? {
		if (!isJsonPrimitive || !asJsonPrimitive.isString) return null
		val value = asString
		return value.takeIf { it.isNotBlank() && it.length <= maxLength && it.none(Char::isISOControl) }
	}
}
