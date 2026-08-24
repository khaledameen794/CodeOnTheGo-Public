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
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * The only boundary that may materialize a reviewed [ProjectCreationPlan].
 *
 * Android code adapts [ProjectCreationGateway] to the existing template flow.
 * This coordinator intentionally has no dependency on Android, Termux, a
 * language model, or a shell.
 */
class AgentProjectCreationCoordinator(
	private val projectNamePolicy: ProjectNamePolicy,
	private val projectPathPolicy: TermuxProjectPathPolicy,
	private val gateway: ProjectCreationGateway,
) {
	private val lock = Any()
	private val records = mutableMapOf<UUID, PlanRecord>()

	fun prepare(request: AgentProjectCreationRequest): ProjectCreationPreparationResult {
		if (request.templateToken.isBlank()) {
			return ProjectCreationPreparationResult.RejectedRequest(
				ProjectRequestRejection.EMPTY_TEMPLATE_TOKEN,
			)
		}
		val safeName =
			when (val nameResult = projectNamePolicy.validate(request.projectName)) {
				is ProjectNameValidationResult.Accepted -> nameResult.name
				is ProjectNameValidationResult.Rejected -> {
					return ProjectCreationPreparationResult.RejectedName(nameResult.reason)
				}
			}

		val pathResult = projectPathPolicy.validate(safeName)
		if (pathResult is ProjectPathValidationResult.Rejected) {
			return ProjectCreationPreparationResult.RejectedPath(pathResult.reason)
		}
		pathResult as ProjectPathValidationResult.Accepted

		val plan =
			ProjectCreationPlan(
				id = UUID.randomUUID(),
				request = request,
				safeProjectName = safeName,
				canonicalRoot = pathResult.canonicalRoot,
				projectDirectory = pathResult.projectDirectory,
			)
		synchronized(lock) {
			records[plan.id] = PlanRecord(plan, AgentProjectCreationState.AWAITING_APPROVAL)
		}
		return ProjectCreationPreparationResult.AwaitingApproval(plan)
	}

	fun approve(planId: UUID): ProjectCreationApprovalResult =
		synchronized(lock) {
			val record = records[planId] ?: return ProjectCreationApprovalResult.UnknownPlan
			if (record.state != AgentProjectCreationState.AWAITING_APPROVAL) {
				return ProjectCreationApprovalResult.NotApprovable(record.state)
			}
			record.state = AgentProjectCreationState.APPROVED
			ProjectCreationApprovalResult.Approved(record.plan)
		}

	fun cancel(planId: UUID): ProjectCreationCancellationResult =
		synchronized(lock) {
			val record = records[planId] ?: return ProjectCreationCancellationResult.UnknownPlan
			if (
				record.state != AgentProjectCreationState.AWAITING_APPROVAL &&
				record.state != AgentProjectCreationState.APPROVED
			) {
				return ProjectCreationCancellationResult.NotCancellable(record.state)
			}
			record.state = AgentProjectCreationState.CANCELLED
			ProjectCreationCancellationResult.Cancelled
		}

	fun state(planId: UUID): AgentProjectCreationState? = synchronized(lock) { records[planId]?.state }

	fun execute(planId: UUID): CompletionStage<ProjectCreationExecutionResult> {
		val plan =
			synchronized(lock) {
				val record = records[planId]
					?: return CompletableFuture.completedFuture(ProjectCreationExecutionResult.UnknownPlan)
				when (record.state) {
					AgentProjectCreationState.AWAITING_APPROVAL -> {
						return CompletableFuture.completedFuture(ProjectCreationExecutionResult.ApprovalRequired)
					}
					AgentProjectCreationState.CREATING -> {
						return CompletableFuture.completedFuture(ProjectCreationExecutionResult.AlreadyExecuting)
					}
					AgentProjectCreationState.APPROVED -> {
						record.state = AgentProjectCreationState.CREATING
						record.plan
					}
					else -> {
						return CompletableFuture.completedFuture(
							ProjectCreationExecutionResult.NotExecutable(record.state),
						)
					}
				}
			}
		val currentPath = projectPathPolicy.validate(plan.safeProjectName)
		if (
			currentPath !is ProjectPathValidationResult.Accepted ||
			currentPath.canonicalRoot != plan.canonicalRoot ||
			currentPath.projectDirectory != plan.projectDirectory
		) {
			return completeFailure(plan.id, "Project directory is no longer available")
		}

		val gatewayResult =
			try {
				gateway.create(plan)
			} catch (error: Throwable) {
				return completeFailure(plan.id, error.message ?: "Project creation could not start")
			}

		return gatewayResult.handle { result, error ->
			if (error != null) {
				return@handle finishFailure(plan.id, error.message ?: "Project creation failed")
			}
			when (result) {
				is ProjectCreationGatewayResult.Failed -> finishFailure(plan.id, result.message)
				is ProjectCreationGatewayResult.Created -> {
					if (matchesPlannedDirectory(plan, result.receipt.projectDirectory)) {
						finishCreated(plan.id, plan, result.receipt)
					} else {
						finishFailure(plan.id, "Project creation returned an unexpected directory")
					}
				}
			}
		}
	}

	private fun completeFailure(
		planId: UUID,
		message: String,
	): CompletionStage<ProjectCreationExecutionResult> =
		CompletableFuture.completedFuture(finishFailure(planId, message))

	private fun finishCreated(
		planId: UUID,
		plan: ProjectCreationPlan,
		receipt: ProjectCreationReceipt,
	): ProjectCreationExecutionResult =
		synchronized(lock) {
			records[planId]?.state = AgentProjectCreationState.CREATED
			ProjectCreationExecutionResult.Created(plan, receipt)
		}

	private fun finishFailure(
		planId: UUID,
		message: String,
	): ProjectCreationExecutionResult =
		synchronized(lock) {
			records[planId]?.state = AgentProjectCreationState.FAILED
			ProjectCreationExecutionResult.Failed(message)
		}

	private fun matchesPlannedDirectory(
		plan: ProjectCreationPlan,
		actualDirectory: Path,
	): Boolean =
		try {
			Files.isDirectory(actualDirectory) &&
				actualDirectory.toRealPath() == plan.projectDirectory.toRealPath()
		} catch (_: IOException) {
			false
		}

	private data class PlanRecord(
		val plan: ProjectCreationPlan,
		var state: AgentProjectCreationState,
	)
}

/**
 * Android-specific code implements this with ProjectCreationManager and the
 * template parameter mapper. The implementation must never construct a shell
 * command from the plan.
 */
fun interface ProjectCreationGateway {
	fun create(plan: ProjectCreationPlan): CompletionStage<ProjectCreationGatewayResult>
}
