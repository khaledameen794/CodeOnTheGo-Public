package com.itsaky.androidide.git.core

import org.eclipse.jgit.transport.URIish
import java.net.URISyntaxException

/**
 * Parses and validates a raw string into a Git repository URL.
 *
 * This method leverages JGit's [URIish] to handle various Git URL formats,
 * including SCP-like SSH URLs. It enforces the presence of either a host or
 * a scheme to prevent plain clipboard text from being incorrectly treated as a valid URI.
 *
 * @param rawText The raw string input to be parsed.
 * @return The normalized Git URL string, or null if the input is invalid or parsing fails.
 */
fun parseGitRepositoryUrl(rawText: String): String? {
	val candidate = rawText.trim()
	if (candidate.isBlank() || candidate.length > 2_048) return null
	if (candidate.any(Char::isISOControl) || '?' in candidate || '#' in candidate) return null

	return try {
		val uri = URIish(candidate)
		val scheme = uri.scheme?.lowercase()
		val isStandardSshUser =
			uri.user.equals("git", ignoreCase = true) && (scheme == null || scheme == "ssh")
		if (uri.pass != null || (!uri.user.isNullOrBlank() && !isStandardSshUser)) return null

		if (uri.host != null || uri.scheme != null) {
			uri.toString()
		} else {
			null
		}
	} catch (_: URISyntaxException) {
		null
	}
}
