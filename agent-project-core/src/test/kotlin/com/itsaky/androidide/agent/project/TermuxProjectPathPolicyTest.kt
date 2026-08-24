package com.itsaky.androidide.agent.project

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TermuxProjectPathPolicyTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	@Test
	fun createsPlanForDirectChildOfExistingWorkspace() {
		val workspace = temporaryFolder.newFolder("ApplicationAndroid").toPath()
		val result = TermuxProjectPathPolicy(workspace).validate(SafeProjectName("SampleProject"))

		assertTrue(result is ProjectPathValidationResult.Accepted)
		result as ProjectPathValidationResult.Accepted
		assertEquals(workspace.toRealPath(), result.canonicalRoot)
		assertEquals(workspace.resolve("SampleProject"), result.projectDirectory)
	}

	@Test
	fun rejectsMissingRootAndExistingProjectDirectory() {
		val missing = temporaryFolder.root.toPath().resolve("missing")
		val missingResult = TermuxProjectPathPolicy(missing).validate(SafeProjectName("SampleProject"))
		assertRejected(missingResult, ProjectPathRejection.ROOT_MISSING)

		val workspace = temporaryFolder.newFolder("ApplicationAndroid").toPath()
		Files.createDirectory(workspace.resolve("SampleProject"))
		val collisionResult = TermuxProjectPathPolicy(workspace).validate(SafeProjectName("SampleProject"))
		assertRejected(collisionResult, ProjectPathRejection.TARGET_ALREADY_EXISTS)
	}

	private fun assertRejected(
		result: ProjectPathValidationResult,
		reason: ProjectPathRejection,
	) {
		assertTrue(result is ProjectPathValidationResult.Rejected)
		assertEquals(reason, (result as ProjectPathValidationResult.Rejected).reason)
	}
}
