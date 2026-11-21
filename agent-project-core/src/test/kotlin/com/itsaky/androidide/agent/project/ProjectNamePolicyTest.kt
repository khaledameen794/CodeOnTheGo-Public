package com.itsaky.androidide.agent.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectNamePolicyTest {
	private val policy = ProjectNamePolicy()

	@Test
	fun acceptsAsciiGradleFriendlyProjectName() {
		val result = policy.validate("Weather_Client-2")

		assertEquals(
			ProjectNameValidationResult.Accepted(SafeProjectName("Weather_Client-2")),
			result,
		)
		assertEquals(
			ProjectNameValidationResult.Accepted(SafeProjectName("My Application")),
			policy.validate("My Application"),
		)
		assertEquals(
			ProjectNameValidationResult.Accepted(SafeProjectName("مشروع خالد")),
			policy.validate("مشروع خالد"),
		)
	}

	@Test
	fun rejectsUnsafeNamesWithSpecificReasons() {
		assertRejected("", ProjectNameRejection.EMPTY)
		assertRejected("   ", ProjectNameRejection.EMPTY)
		assertRejected("../outside", ProjectNameRejection.PATH_SEPARATOR)
		assertRejected("..hidden", ProjectNameRejection.TRAVERSAL_SEQUENCE)
		assertRejected("nested/project", ProjectNameRejection.PATH_SEPARATOR)
		assertRejected("nested\\project", ProjectNameRejection.PATH_SEPARATOR)
		assertRejected("/absolute", ProjectNameRejection.PATH_SEPARATOR)
		assertRejected("7start", ProjectNameRejection.INVALID_START)
		assertRejected(" leading", ProjectNameRejection.INVALID_CHARACTER)
		assertRejected("trailing ", ProjectNameRejection.INVALID_CHARACTER)
		assertRejected("name\u0000", ProjectNameRejection.INVALID_CHARACTER)
		assertRejected("a".repeat(65), ProjectNameRejection.TOO_LONG)
	}

	private fun assertRejected(
		candidate: String,
		reason: ProjectNameRejection,
	) {
		val result = policy.validate(candidate)
		assertTrue(result is ProjectNameValidationResult.Rejected)
		assertEquals(reason, (result as ProjectNameValidationResult.Rejected).reason)
	}
}
