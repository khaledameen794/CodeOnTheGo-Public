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

import java.nio.file.Path
import java.util.UUID

/**
 * The only project-creation input accepted from an agent conversation.
 *
 * Neither a filesystem root nor a shell command may enter this boundary.
 */
data class AgentProjectCreationRequest(
	val projectName: String,
	val templateToken: String,
	val packageName: String? = null,
	val language: String? = null,
)

/** A project directory name accepted by [ProjectNamePolicy]. */
@JvmInline
value class SafeProjectName internal constructor(
	val value: String,
)

enum class ProjectNameRejection {
	EMPTY,
	TOO_LONG,
	PATH_SEPARATOR,
	TRAVERSAL_SEQUENCE,
	INVALID_START,
	INVALID_CHARACTER,
}

enum class ProjectRequestRejection {
	EMPTY_TEMPLATE_TOKEN,
}

sealed interface ProjectNameValidationResult {
	data class Accepted(
		val name: SafeProjectName,
	) : ProjectNameValidationResult

	data class Rejected(
		val reason: ProjectNameRejection,
	) : ProjectNameValidationResult
}

enum class ProjectPathRejection {
	ROOT_MISSING,
	ROOT_NOT_DIRECTORY,
	ROOT_UNRESOLVABLE,
	PATH_ESCAPES_ROOT,
	TARGET_ALREADY_EXISTS,
}

sealed interface ProjectPathValidationResult {
	data class Accepted(
		val canonicalRoot: Path,
		val projectDirectory: Path,
	) : ProjectPathValidationResult

	data class Rejected(
		val reason: ProjectPathRejection,
	) : ProjectPathValidationResult
}

/** A reviewed, immutable creation request that still requires explicit user approval. */
class ProjectCreationPlan internal constructor(
	val id: UUID,
	val request: AgentProjectCreationRequest,
	val safeProjectName: SafeProjectName,
	val canonicalRoot: Path,
	val projectDirectory: Path,
)

enum class AgentProjectCreationState {
	AWAITING_APPROVAL,
	APPROVED,
	CREATING,
	CREATED,
	REJECTED,
	FAILED,
	CANCELLED,
}

sealed interface ProjectCreationPreparationResult {
	data class AwaitingApproval(
		val plan: ProjectCreationPlan,
	) : ProjectCreationPreparationResult

	data class RejectedRequest(
		val reason: ProjectRequestRejection,
	) : ProjectCreationPreparationResult

	data class RejectedName(
		val reason: ProjectNameRejection,
	) : ProjectCreationPreparationResult

	data class RejectedPath(
		val reason: ProjectPathRejection,
	) : ProjectCreationPreparationResult
}

sealed interface ProjectCreationApprovalResult {
	data class Approved(
		val plan: ProjectCreationPlan,
	) : ProjectCreationApprovalResult

	data class NotApprovable(
		val state: AgentProjectCreationState,
	) : ProjectCreationApprovalResult

	data object UnknownPlan : ProjectCreationApprovalResult
}

sealed interface ProjectCreationCancellationResult {
	data object Cancelled : ProjectCreationCancellationResult

	data class NotCancellable(
		val state: AgentProjectCreationState,
	) : ProjectCreationCancellationResult

	data object UnknownPlan : ProjectCreationCancellationResult
}

/**
 * Data reported by the platform adapter after its template transaction completes.
 *
 * The coordinator verifies [projectDirectory] before exposing this receipt to an
 * Android opener or terminal launcher.
 */
data class ProjectCreationReceipt(
	val projectDirectory: Path,
	val hasTemplateIssues: Boolean = false,
	val localGitInitialized: Boolean = false,
)

sealed interface ProjectCreationGatewayResult {
	/** The adapter created the project and reports a verifiable receipt. */
	data class Created(
		val receipt: ProjectCreationReceipt,
	) : ProjectCreationGatewayResult

	data class Failed(
		val message: String,
	) : ProjectCreationGatewayResult
}

sealed interface ProjectCreationExecutionResult {
	data class Created(
		val plan: ProjectCreationPlan,
		val receipt: ProjectCreationReceipt,
	) : ProjectCreationExecutionResult

	data class Failed(
		val message: String,
	) : ProjectCreationExecutionResult

	data object ApprovalRequired : ProjectCreationExecutionResult

	data object AlreadyExecuting : ProjectCreationExecutionResult

	data class NotExecutable(
		val state: AgentProjectCreationState,
	) : ProjectCreationExecutionResult

	data object UnknownPlan : ProjectCreationExecutionResult
}
