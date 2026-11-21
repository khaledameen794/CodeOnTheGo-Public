package com.itsaky.androidide.utils

import android.content.Context
import com.itsaky.androidide.R
import com.itsaky.androidide.git.core.GitRepositoryManager
import com.itsaky.androidide.roomData.recentproject.RecentProject
import com.itsaky.androidide.tasks.executeAsyncProvideError
import com.itsaky.androidide.templates.ProjectTemplateRecipeResult
import com.itsaky.androidide.templates.StringParameter
import com.itsaky.androidide.templates.Template
import com.itsaky.androidide.templates.impl.ConstraintVerifier
import org.slf4j.LoggerFactory

class ProjectCreationManager(
	private val context: Context,
) {
	private val log = LoggerFactory.getLogger(ProjectCreationManager::class.java)

	fun execute(
		template: Template<*>,
		onStart: () -> Unit,
		onSuccess: (ProjectTemplateRecipeResult, RecentProject, Boolean) -> Unit,
		onError: (String) -> Unit,
	) {
		val isValid =
			template.parameters.filterIsInstance<StringParameter>().all { param ->
				ConstraintVerifier.isValid(param.value, param.constraints)
			}

		if (!isValid) {
			onError(context.getString(R.string.msg_invalid_project_details))
			return
		}

		onStart()

		executeAsyncProvideError({
			val result = template.recipe.execute(TemplateRecipeExecutor(context.applicationContext))
			if (result !is ProjectTemplateRecipeResult) {
				throw IllegalStateException("Template did not create a project")
			}
			val repository =
				try {
					GitRepositoryManager.initializeFreshProjectRepositoryBlocking(result.data.projectDir)
				} catch (error: Exception) {
					log.warn("Local Git initialization failed; project creation will continue", error)
					null
				}
			val localGitInitialized = repository != null
			repository?.let { runCatching { it.close() } }
			ProjectCreationWorkResult(result, localGitInitialized)
		}) { workResult, err ->
			if (workResult == null || err != null) {
				err?.printStackTrace()
				val errorMsg = err?.cause?.message ?: err?.message ?: context.getString(R.string.project_creation_failed)
				onError(errorMsg)
				return@executeAsyncProvideError
			}

			val result = workResult.projectResult

			val now = System.currentTimeMillis().toString()
			val project =
				RecentProject(
					location = result.data.projectDir.path,
					name = result.data.name,
					createdAt = now,
					lastModified = now,
					templateName = template.templateNameStr,
					language = result.data.language?.name ?: "unknown",
				)

			onSuccess(result, project, workResult.localGitInitialized)
		}
	}
}

private data class ProjectCreationWorkResult(
	val projectResult: ProjectTemplateRecipeResult,
	val localGitInitialized: Boolean,
)
