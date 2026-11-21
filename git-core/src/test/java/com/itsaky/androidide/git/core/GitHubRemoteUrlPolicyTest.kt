package com.itsaky.androidide.git.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubRemoteUrlPolicyTest {
	@Test
	fun normalizesHttpsGithubRepositoryUrls() {
		assertEquals(
			"https://github.com/owner/project.git",
			GitHubRemoteUrlPolicy.normalize(" https://github.com/owner/project "),
		)
		assertEquals(
			"https://github.com/Owner/project.git",
			GitHubRemoteUrlPolicy.normalize("https://GITHUB.com/Owner/project.git/"),
		)
	}

	@Test
	fun rejectsCredentialsAndHiddenUrlParameters() {
		assertNull(GitHubRemoteUrlPolicy.normalize("https://user:token@github.com/owner/project.git"))
		assertNull(GitHubRemoteUrlPolicy.normalize("https://token@github.com/owner/project.git"))
		assertNull(GitHubRemoteUrlPolicy.normalize("https://github.com/owner/project.git?token=fake"))
		assertNull(GitHubRemoteUrlPolicy.normalize("https://github.com/owner/project.git#token"))
	}

	@Test
	fun rejectsUnsupportedHostsSchemesAndPaths() {
		assertNull(GitHubRemoteUrlPolicy.normalize("http://github.com/owner/project.git"))
		assertNull(GitHubRemoteUrlPolicy.normalize("https://example.com/owner/project.git"))
		assertNull(GitHubRemoteUrlPolicy.normalize("https://github.com/owner/project/extra.git"))
		assertNull(GitHubRemoteUrlPolicy.normalize("https://github.com/owner/%2e%2e.git"))
		assertNull(GitHubRemoteUrlPolicy.normalize("https://github.com/owner/project.git:443"))
	}
}
