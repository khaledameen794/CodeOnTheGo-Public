package com.itsaky.androidide.agent.project

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentProjectSuggestionTest {
	private val expectedFields = setOf("projectName", "packageName", "language")

	@Test
	fun parsesOnlyTheRequestedTemplateValues() {
		val result =
			AgentProjectSuggestionParser.parse(
				"""{"projectName":"Weather App","packageName":"org.example.weather","language":"Kotlin"}""",
				expectedFields,
			)

		assertEquals(
			AgentProjectSuggestionParseResult.Parsed(
				AgentProjectSuggestion("Weather App", "org.example.weather", "Kotlin"),
			),
			result,
		)
	}

	@Test
	fun acceptsJsonCodeFenceAndExplicitNullsForOptionalValues() {
		val result =
			AgentProjectSuggestionParser.parse(
				"""```json
{"projectName":"Sample","packageName":null,"language":null}
```""",
				expectedFields,
			)

		assertEquals(
			AgentProjectSuggestionParseResult.Parsed(AgentProjectSuggestion("Sample", null, null)),
			result,
		)
	}

	@Test
	fun rejectsUnrequestedFieldsIncludingPathsAndCommands() {
		val result =
			AgentProjectSuggestionParser.parse(
				"""{"projectName":"Sample","packageName":"org.example.sample","language":"Java","path":"../outside","command":"rm -rf"}""",
				expectedFields,
			)

		assertEquals(AgentProjectSuggestionParseResult.InvalidFields, result)
	}

	@Test
	fun rejectsMissingFieldsWrongTypesAndNonJsonReplies() {
		assertEquals(
			AgentProjectSuggestionParseResult.InvalidFields,
			AgentProjectSuggestionParser.parse("""{"projectName":"Sample"}""", expectedFields),
		)
		assertEquals(
			AgentProjectSuggestionParseResult.InvalidValue,
			AgentProjectSuggestionParser.parse(
				"""{"projectName":4,"packageName":null,"language":null}""",
				expectedFields,
			),
		)
		assertEquals(
			AgentProjectSuggestionParseResult.InvalidJson,
			AgentProjectSuggestionParser.parse("Here is a project idea.", expectedFields),
		)
	}

	@Test
	fun rejectsUnsupportedExpectedFieldsAndOversizedResponses() {
		assertEquals(
			AgentProjectSuggestionParseResult.InvalidFields,
			AgentProjectSuggestionParser.parse("{}", setOf("projectName", "filesystemPath")),
		)
		assertEquals(
			AgentProjectSuggestionParseResult.ResponseTooLarge,
			AgentProjectSuggestionParser.parse("x".repeat(8_193), expectedFields),
		)
	}
}
