package com.itsaky.androidide.git.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitRepositoryUrlsTest {
	@Test
	fun acceptsNormalHttpsAndSshGitUrls() {
		assertEquals(
			"https://github.com/owner/project.git",
			parseGitRepositoryUrl("https://github.com/owner/project.git"),
		)
		assertEquals(
			"git@github.com:owner/project.git",
			parseGitRepositoryUrl("git@github.com:owner/project.git"),
		)
	}

	@Test
	fun rejectsCredentialsAndQueryParametersWithoutEchoingThem() {
		assertNull(parseGitRepositoryUrl("https://user:fake-token@github.com/owner/project.git"))
		assertNull(parseGitRepositoryUrl("https://fake-token@github.com/owner/project.git"))
		assertNull(parseGitRepositoryUrl("ssh://fake-token@github.com/owner/project.git"))
		assertNull(parseGitRepositoryUrl("git+https://fake-token@github.com/owner/project.git"))
		assertNull(parseGitRepositoryUrl("https://github.com/owner/project.git?token=fake-token"))
	}
}
