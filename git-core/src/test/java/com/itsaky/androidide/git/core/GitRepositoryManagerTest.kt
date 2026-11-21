package com.itsaky.androidide.git.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class GitRepositoryManagerTest {
	@Test
	fun initializesMainWithoutConfiguringOrContactingARemote() =
		runBlocking {
			val project = Files.createTempDirectory("git-project").toFile()
			try {
				GitRepositoryManager.initializeRepository(project).use { repository ->
					assertEquals(project.canonicalFile, repository.rootDir.canonicalFile)
					assertEquals("main", repository.getCurrentBranch()?.name)
					assertFalse(repository.hasOrigin())
				}
				assertTrue(project.resolve(".git/config").isFile)
				assertFalse(project.resolve(".git/config").readText().contains("github.com"))
			} finally {
				project.deleteRecursively()
			}
		}

	@Test
	fun initializesAProjectInsideAnotherRepositoryIndependently() {
		val parent = Files.createTempDirectory("git-parent").toFile()
		val child = parent.resolve("child").apply { mkdir() }
		try {
			GitRepositoryManager.initializeRepositoryBlocking(parent).close()
			assertTrue(runBlocking { GitRepositoryManager.isRepository(parent) })
			assertFalse(runBlocking { GitRepositoryManager.isRepository(child) })
			assertNull(runBlocking { GitRepositoryManager.openRepository(child) })

			GitRepositoryManager.initializeRepositoryBlocking(child).use { repository ->
				assertEquals(child.canonicalFile, repository.rootDir.canonicalFile)
				assertFalse(runBlocking { repository.hasOrigin() })
			}
		} finally {
			parent.deleteRecursively()
		}
	}

	@Test
	fun storesOnlyAValidatedCredentialFreeGithubOrigin() =
		runBlocking {
			val project = Files.createTempDirectory("git-origin").toFile()
			try {
				GitRepositoryManager.initializeRepository(project).use { repository ->
					repository.setOriginUrl("https://github.com/owner/project")
					assertTrue(repository.hasOrigin())
					repository.setOriginUrl("https://github.com/owner/another-project.git")
				}
				val config = project.resolve(".git/config").readText()
				assertTrue(config.contains("https://github.com/owner/another-project.git"))
				assertFalse(config.contains("token"))
			} finally {
				project.deleteRecursively()
			}
		}

	@Test
	fun rejectsCredentialBearingOriginWithoutWritingIt() =
		runBlocking {
			val project = Files.createTempDirectory("git-origin-secret").toFile()
			try {
				GitRepositoryManager.initializeRepository(project).use { repository ->
					val failure =
						runCatching {
							repository.setOriginUrl("https://user:fake-token@github.com/owner/project.git")
						}.exceptionOrNull()
					assertTrue(failure is IllegalArgumentException)
					assertFalse(repository.hasOrigin())
				}
				val config = project.resolve(".git/config").readText()
				assertFalse(config.contains("fake-token"))
			} finally {
				project.deleteRecursively()
			}
		}

	@Test
	fun freshProjectInitializationDoesNotReuseAnExistingRepository() =
		runBlocking {
			val project = Files.createTempDirectory("git-existing-project").toFile()
			try {
				GitRepositoryManager.initializeRepository(project).use { repository ->
					repository.setOriginUrl("https://github.com/owner/existing.git")
				}

				val failure =
					runCatching {
						GitRepositoryManager.initializeFreshProjectRepositoryBlocking(project)
					}.exceptionOrNull()
				assertTrue(failure is java.io.IOException)

				GitRepositoryManager.openRepository(project)!!.use { repository ->
					assertEquals("main", repository.getCurrentBranch()?.name)
					assertTrue(repository.hasOrigin())
				}
			} finally {
				project.deleteRecursively()
			}
		}

	@Test
	fun doesNotTreatLegacyCredentialBearingOriginAsUsable() =
		runBlocking {
			val project = Files.createTempDirectory("git-legacy-origin-secret").toFile()
			try {
				GitRepositoryManager.initializeRepository(project).close()
				project.resolve(".git/config").appendText(
					"\n[remote \"origin\"]\n\turl = https://user:legacy-token@github.com/owner/project.git\n",
				)

				val repository = GitRepositoryManager.openRepository(project)!!
				try {
					assertFalse(repository.hasOrigin())
				} finally {
					repository.close()
				}
			} finally {
				project.deleteRecursively()
			}
		}
}
