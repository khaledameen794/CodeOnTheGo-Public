/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.fragments

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.itsaky.androidide.R
import com.itsaky.androidide.adapters.TemplateListAdapter
import com.itsaky.androidide.agent.project.TemplateSessionRegistry
import com.itsaky.androidide.databinding.FragmentTemplateListBinding
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag.EXIT_TO_MAIN
import com.itsaky.androidide.templates.ITemplateProvider
import com.itsaky.androidide.templates.ITemplateWidgetViewProvider
import com.itsaky.androidide.templates.ProjectTemplate
import com.itsaky.androidide.templates.impl.TemplateProviderImpl
import com.itsaky.androidide.templates.impl.zip.ArchiveTemplateSessionFactory
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.slf4j.LoggerFactory

/**
 * A fragment to show the list of available templates.
 *
 * @author Akash Yadav
 */
class TemplateListFragment :
	FragmentWithBinding<FragmentTemplateListBinding>(
		R.layout.fragment_template_list,
		FragmentTemplateListBinding::bind,
	) {
	private var adapter: TemplateListAdapter? = null
	private var reloadJob: Job? = null

	private val viewModel by activityViewModel<MainViewModel>()

	companion object {
		private val log = LoggerFactory.getLogger(TemplateListFragment::class.java)
	}

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)

		val gridLayoutManager = GridLayoutManager(requireContext(), 1)
		binding.list.layoutManager = gridLayoutManager

		binding.exitButton.setOnClickListener {
			viewModel.setScreen(MainViewModel.SCREEN_MAIN)
		}

		binding.exitButton.setOnLongClickListener {
			TooltipManager.showIdeCategoryTooltip(
				context = requireContext(),
				anchorView = binding.root,
				tag = EXIT_TO_MAIN,
			)
			true
		}

		viewModel.currentScreen.observe(viewLifecycleOwner) { current ->
			if (current == MainViewModel.SCREEN_TEMPLATE_DETAILS) {
				return@observe
			}

			reloadTemplates()
		}
	}

	override fun onConfigurationChanged(newConfig: Configuration) {
		super.onConfigurationChanged(newConfig)

		updateSpanCount()
	}

	override fun onResume() {
		super.onResume()
		updateSpanCount()
	}

	private fun updateSpanCount() {
		val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
		val maxSpans = if (isLandscape) 6 else 4

		val itemCount = binding.list.adapter?.itemCount ?: 0

		val optimalSpans = maxOf(1, minOf(maxSpans, itemCount))

		val layoutManager = binding.list.layoutManager as? GridLayoutManager
		if (layoutManager != null && layoutManager.spanCount != optimalSpans) {
			layoutManager.spanCount = optimalSpans
		}
	}

	override fun onDestroyView() {
		super.onDestroyView()
	}

	private fun reloadTemplates() {
		_binding ?: return

		log.debug("Reloading templates...")

		reloadJob?.cancel()
		reloadJob =
			viewLifecycleOwner.lifecycleScope.launch {
				val (templates, warnings, sessionData) =
					withContext(Dispatchers.IO) {
						val provider = ITemplateProvider.getInstance(reload = true)
						// Pre-warm the widget view provider so per-bind getInstance() in
						// TemplateWidgetsListAdapter doesn't trigger a disk read on the UI thread.
						ITemplateWidgetViewProvider.getInstance()
						val templates = provider.getTemplates().filterIsInstance<ProjectTemplate>()
						val warnings = (provider as? TemplateProviderImpl)?.warnings.orEmpty()
						val factory = ArchiveTemplateSessionFactory.fromDirectory(Environment.TEMPLATES_DIR)
						val registry = TemplateSessionRegistry(factory)
						val descriptors = factory.catalog()
						val catalogMatches =
							templates.size == descriptors.size &&
								templates.zip(descriptors).all { (template, descriptor) ->
									template.templateNameStr == descriptor.displayName
								}
						val descriptorsByTemplateId =
							if (catalogMatches) {
								templates.zip(descriptors).associate { (template, descriptor) ->
									template.templateId to descriptor
								}
							} else {
								emptyMap()
							}
						Triple(
							templates,
							warnings,
							registry to (descriptorsByTemplateId to catalogMatches),
						)
					}
				val (registry, tokenResult) = sessionData
				val (descriptorsByTemplateId, catalogMatches) = tokenResult

				_binding ?: return@launch
				viewModel.templateSessionRegistry = registry
				viewModel.selectedTemplateSessionToken = null

				adapter =
					TemplateListAdapter(
						templates = templates,
						onClick = { template, _ ->
							val descriptor = descriptorsByTemplateId[template.templateId]
							if (descriptor == null) {
								requireActivity().flashError(getString(R.string.agent_template_session_unavailable))
							} else {
								val token = registry.issue(listOf(descriptor)).single().token
								viewModel.selectedTemplateSessionToken = token
								viewModel.template.value = template
								viewModel.setScreen(MainViewModel.SCREEN_TEMPLATE_DETAILS)
							}
						},
						onLongClick = { template, itemView ->
							template.tooltipTag?.let { tag ->
								TooltipManager.showIdeCategoryTooltip(
									context = requireContext(),
									anchorView = itemView,
									tag = tag,
								)
							}
						},
					)
				binding.list.adapter = adapter
				updateSpanCount()
				if (!catalogMatches && templates.isNotEmpty()) {
					requireActivity().flashError(getString(R.string.agent_template_session_unavailable))
				}

				if (warnings.isNotEmpty()) {
					requireActivity().flashError(
						warnings.joinToString(System.lineSeparator()) { warning ->
							requireContext().getString(warning.resId, *warning.args.toTypedArray())
						},
					)
				}
			}
	}
}
