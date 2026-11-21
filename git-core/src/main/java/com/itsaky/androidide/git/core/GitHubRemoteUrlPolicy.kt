package com.itsaky.androidide.git.core

import java.net.URI

/** Validates GitHub remotes without accepting credentials or hidden URL parameters. */
object GitHubRemoteUrlPolicy {
	private const val MAX_URL_LENGTH = 2_048
	private val ownerPattern = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,98}[A-Za-z0-9])?")
	private val repositoryPattern = Regex("[A-Za-z0-9](?:[A-Za-z0-9_.-]{0,98}[A-Za-z0-9])?")

	/** Returns a canonical HTTPS GitHub URL, or null when the input is unsafe or unsupported. */
	fun normalize(rawUrl: String): String? {
		val candidate = rawUrl.trim()
		if (candidate.isEmpty() || candidate.length > MAX_URL_LENGTH) return null
		if (candidate.any(Char::isISOControl)) return null

		val uri =
			try {
				URI(candidate)
			} catch (_: Exception) {
				return null
			}

		if (!uri.scheme.equals("https", ignoreCase = true)) return null
		if (!uri.host.equals("github.com", ignoreCase = true)) return null
		if (uri.rawUserInfo != null || uri.port != -1) return null
		if (uri.rawQuery != null || uri.rawFragment != null || uri.isOpaque) return null

		val rawPath = uri.rawPath ?: return null
		if ('%' in rawPath || rawPath.contains("//")) return null
		val path = rawPath.removeSuffix("/").removePrefix("/").removeSuffix(".git")
		val segments = path.split('/')
		if (segments.size != 2) return null

		val (owner, repository) = segments
		if (!ownerPattern.matches(owner) || !repositoryPattern.matches(repository)) return null
		return "https://github.com/$owner/$repository.git"
	}
}
