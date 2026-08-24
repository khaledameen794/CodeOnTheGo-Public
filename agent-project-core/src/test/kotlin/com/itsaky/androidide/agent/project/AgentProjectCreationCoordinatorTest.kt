package com.itsaky.androidide.agent.project

import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentProjectCreationCoordinatorTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	@Test
	fun doesNotInvokeGatewayUntilTheReviewedPlanIsApproved() {
		val gateway = RecordingGateway()
		val coordinator = newCoordinator(gateway)
		val plan = coordinator.prepare(request("Notes")) as ProjectCreationPreparationResult.AwaitingApproval

		val beforeApproval = coordinator.execute(plan.plan.id).toCompletableFuture().join()

		assertEquals(ProjectCreationExecutionResult.ApprovalRequired, beforeApproval)
		assertFalse(gateway.wasInvoked)
		assertEquals(AgentProjectCreationState.AWAITING_APPROVAL, coordinator.state(plan.plan.id))

		assertTrue(coordinator.approve(plan.plan.id) is ProjectCreationApprovalResult.Approved)
		val afterApproval = coordinator.execute(plan.plan.id).toCompletableFuture().join()

		assertTrue(afterApproval is ProjectCreationExecutionResult.Created)
		assertTrue(gateway.wasInvoked)
		assertEquals(AgentProjectCreationState.CREATED, coordinator.state(plan.plan.id))
	}

	@Test
	fun rejectsInvalidNameWithoutPreparingOrInvokingGateway() {
		val gateway = RecordingGateway()
		val coordinator = newCoordinator(gateway)

		val result = coordinator.prepare(request("../escape"))

		assertEquals(
			ProjectCreationPreparationResult.RejectedName(ProjectNameRejection.PATH_SEPARATOR),
			result,
		)
		assertFalse(gateway.wasInvoked)
	}

	@Test
	fun failsWhenGatewayReportsADirectoryDifferentFromTheApprovedPlan() {
		val gateway = RecordingGateway { plan ->
			val unexpected = plan.canonicalRoot.resolve("DifferentProject")
			Files.createDirectory(unexpected)
			ProjectCreationGatewayResult.Created(unexpected)
		}
		val coordinator = newCoordinator(gateway)
		val plan = coordinator.prepare(request("ExpectedProject")) as ProjectCreationPreparationResult.AwaitingApproval

		coordinator.approve(plan.plan.id)
		val result = coordinator.execute(plan.plan.id).toCompletableFuture().join()

		assertTrue(result is ProjectCreationExecutionResult.Failed)
		assertEquals(AgentProjectCreationState.FAILED, coordinator.state(plan.plan.id))
	}

	@Test
	fun refusesExecutionWhenTargetAppearsAfterApproval() {
		val gateway = RecordingGateway()
		val coordinator = newCoordinator(gateway)
		val plan = coordinator.prepare(request("RacingProject")) as ProjectCreationPreparationResult.AwaitingApproval

		coordinator.approve(plan.plan.id)
		Files.createDirectory(plan.plan.projectDirectory)
		val result = coordinator.execute(plan.plan.id).toCompletableFuture().join()

		assertTrue(result is ProjectCreationExecutionResult.Failed)
		assertFalse(gateway.wasInvoked)
		assertEquals(AgentProjectCreationState.FAILED, coordinator.state(plan.plan.id))
	}

	private fun newCoordinator(gateway: ProjectCreationGateway): AgentProjectCreationCoordinator {
		val workspace = temporaryFolder.newFolder("ApplicationAndroid").toPath()
		return AgentProjectCreationCoordinator(
			projectNamePolicy = ProjectNamePolicy(),
			projectPathPolicy = TermuxProjectPathPolicy(workspace),
			gateway = gateway,
		)
	}

	private fun request(name: String) =
		AgentProjectCreationRequest(
			projectName = name,
			templateId = "android-empty-activity",
		)

	private class RecordingGateway(
		private val behavior: ((ProjectCreationPlan) -> ProjectCreationGatewayResult)? = null,
	) : ProjectCreationGateway {
		var wasInvoked = false

		override fun create(plan: ProjectCreationPlan): CompletionStage<ProjectCreationGatewayResult> {
			wasInvoked = true
			val result =
				behavior?.invoke(plan)
					?: run {
						Files.createDirectory(plan.projectDirectory)
						ProjectCreationGatewayResult.Created(plan.projectDirectory)
					}
			return CompletableFuture.completedFuture(result)
		}
	}
}
