/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidIDE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.agent.project

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/**
 * Derives exactly one direct-child project path from a trusted workspace root.
 *
 * The root comes from application configuration, never the model or a chat
 * message. Resolving it to a real path prevents an existing root symlink from
 * changing the containment boundary during planning.
 */
class TermuxProjectPathPolicy(private val workspaceRoot: Path) {
	fun validate(projectName: SafeProjectName): ProjectPathValidationResult {
		if (!Files.exists(workspaceRoot, NOFOLLOW_LINKS)) {
			return ProjectPathValidationResult.Rejected(ProjectPathRejection.ROOT_MISSING)
		}
		if (!Files.isDirectory(workspaceRoot, NOFOLLOW_LINKS)) {
			return ProjectPathValidationResult.Rejected(ProjectPathRejection.ROOT_NOT_DIRECTORY)
		}

		val canonicalRoot =
			try {
				workspaceRoot.toRealPath()
			} catch (_: IOException) {
				return ProjectPathValidationResult.Rejected(ProjectPathRejection.ROOT_UNRESOLVABLE)
			}
		val destination = canonicalRoot.resolve(projectName.value).normalize()

		if (destination.parent != canonicalRoot || !destination.startsWith(canonicalRoot)) {
			return ProjectPathValidationResult.Rejected(ProjectPathRejection.PATH_ESCAPES_ROOT)
		}
		if (Files.exists(destination, NOFOLLOW_LINKS)) {
			return ProjectPathValidationResult.Rejected(ProjectPathRejection.TARGET_ALREADY_EXISTS)
		}

		return ProjectPathValidationResult.Accepted(canonicalRoot, destination)
	}
}
