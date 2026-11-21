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

/**
 * Validates a single directory name. The value is never trimmed or normalized:
 * the user must explicitly review the exact folder name that will be created.
 */
class ProjectNamePolicy {
	fun validate(candidate: String): ProjectNameValidationResult {
		if (candidate.isBlank()) {
			return ProjectNameValidationResult.Rejected(ProjectNameRejection.EMPTY)
		}
		if (candidate.length > MAX_LENGTH) {
			return ProjectNameValidationResult.Rejected(ProjectNameRejection.TOO_LONG)
		}
		if (candidate != candidate.trim()) {
			return ProjectNameValidationResult.Rejected(ProjectNameRejection.INVALID_CHARACTER)
		}
		if (candidate.contains('/') || candidate.contains('\\')) {
			return ProjectNameValidationResult.Rejected(ProjectNameRejection.PATH_SEPARATOR)
		}
		if (candidate.contains("..")) {
			return ProjectNameValidationResult.Rejected(ProjectNameRejection.TRAVERSAL_SEQUENCE)
		}
		if (!Character.isLetter(candidate.codePointAt(0))) {
			return ProjectNameValidationResult.Rejected(ProjectNameRejection.INVALID_START)
		}
		if (!NAME_PATTERN.matches(candidate)) {
			return ProjectNameValidationResult.Rejected(ProjectNameRejection.INVALID_CHARACTER)
		}

		return ProjectNameValidationResult.Accepted(SafeProjectName(candidate))
	}

	private companion object {
		const val MAX_LENGTH = 64
		val NAME_PATTERN = Regex("^[\\p{L}][\\p{L}\\p{N} _-]{0,63}$")
	}
}
