package com.itsaky.androidide.git.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.CredentialsProvider
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Utility class for detecting and opening Git repositories.
 */
object GitRepositoryManager {
	/**
	 * Checks if a directory is a Git repository.
	 */
	suspend fun isRepository(dir: File): Boolean =
		withContext(Dispatchers.IO) {
			isRepositoryAtRoot(dir)
		}

	private fun isRepositoryAtRoot(dir: File): Boolean =
		try {
			val canonicalDir = dir.canonicalFile
			FileRepositoryBuilder()
				.findGitDir(canonicalDir)
				.build()
				.use { repository ->
					repository.workTree?.canonicalFile == canonicalDir &&
						repository.objectDatabase.exists()
				}
		} catch (_: Exception) {
			false
		}

	/**
	 * Opens a Git repository at the given directory.
	 * Returns null if no repository is found.
	 */
	suspend fun openRepository(dir: File): GitRepository? =
		withContext(Dispatchers.IO) {
			if (isRepositoryAtRoot(dir)) {
				JGitRepository(dir)
			} else {
				null
			}
		}

	/**
	 * Creates a local repository with the initial branch named `main`.
	 * No remote is configured and no network operation is performed.
	 */
	fun initializeRepositoryBlocking(dir: File): GitRepository {
		val canonicalDir = dir.canonicalFile
		if (!canonicalDir.isDirectory) {
			throw IOException("Project directory is not available")
		}
		if (isRepositoryAtRoot(canonicalDir)) {
			return JGitRepository(canonicalDir)
		}

		return createRepositoryAtRoot(canonicalDir)
	}

	private fun createRepositoryAtRoot(canonicalDir: File): GitRepository {
		val gitEntry = File(canonicalDir, ".git")
		if (gitEntry.exists() || Files.isSymbolicLink(gitEntry.toPath())) {
			throw IOException("Project contains an unreadable Git directory")
		}

		Git
			.init()
			.setDirectory(canonicalDir)
			.setInitialBranch("main")
			.call()
			.close()

		if (!isRepositoryAtRoot(canonicalDir)) {
			throw IOException("Local Git repository could not be opened")
		}
		return JGitRepository(canonicalDir)
	}

	/** Initializes a template-created project only when it does not already contain a repository. */
	fun initializeFreshProjectRepositoryBlocking(dir: File): GitRepository {
		val canonicalDir = dir.canonicalFile
		if (!canonicalDir.isDirectory) {
			throw IOException("Project directory is not available")
		}
		if (isRepositoryAtRoot(canonicalDir)) {
			throw IOException("Project already contains a Git repository")
		}
		return createRepositoryAtRoot(canonicalDir)
	}

	/** Initializes a local repository on the I/O dispatcher. */
	suspend fun initializeRepository(dir: File): GitRepository =
		withContext(Dispatchers.IO) {
			initializeRepositoryBlocking(dir)
		}

	/**
	 * Clones a Git repository from the given URL to the specified directory.
	 *
	 * @param url The URL of the repository to clone.
	 * @param destDir The destination directory.
	 * @param credentialsProvider Optional credentials provider for authentication.
	 * @return The cloned [GitRepository].
	 */
	suspend fun cloneRepository(
		url: String,
		destDir: File,
		credentialsProvider: CredentialsProvider? = null,
		progressMonitor: ProgressMonitor? = null,
	): GitRepository =
		withContext(Dispatchers.IO) {
			val cloneCommand =
				Git
					.cloneRepository()
					.setURI(url)
					.setDirectory(destDir)
					.setCloneAllBranches(true)

			if (progressMonitor != null) {
				cloneCommand.setProgressMonitor(progressMonitor)
			}

			if (credentialsProvider != null) {
				cloneCommand.setCredentialsProvider(credentialsProvider)
			}

			try {
				cloneCommand.call().close()
				JGitRepository(destDir)
			} catch (e: Exception) {
				throw e
			}
		}
}
