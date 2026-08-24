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

package com.itsaky.androidide.templates.impl.zip

import com.itsaky.androidide.agent.project.IsolatedTemplateSessionFactory
import com.itsaky.androidide.agent.project.TemplateSessionDescriptor
import com.itsaky.androidide.templates.ProjectTemplate
import com.itsaky.androidide.templates.impl.TemplateWarning
import org.adfa.constants.TEMPLATE_ARCHIVE_EXTENSION
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Creates a fresh [ProjectTemplate] for each agent session token.
 *
 * The regular [com.itsaky.androidide.templates.ITemplateProvider] keeps mutable
 * templates in its cache for the UI. This factory deliberately does not borrow
 * from that cache: it records only a trusted archive location, a template
 * position, and a light-weight archive fingerprint, then re-reads the archive
 * when [create] is called.
 */
class ArchiveTemplateSessionFactory(
	private val archives: () -> List<File>,
) : IsolatedTemplateSessionFactory<ProjectTemplate> {
	private val lock = Any()
	private val locators = mutableMapOf<String, TemplateLocator>()

	/**
	 * Enumerates installed templates and replaces the current session catalog.
	 * Returned source IDs are opaque and are meaningful only to this factory.
	 */
	fun catalog(): List<TemplateSessionDescriptor> =
		synchronized(lock) {
			locators.clear()
			archives()
				.filter { it.isFile && it.extension.equals(TEMPLATE_ARCHIVE_EXTENSION, ignoreCase = true) }
				.flatMap { archive ->
					val canonicalArchive = archive.canonicalFile
					val fingerprint = ArchiveFingerprint.from(canonicalArchive)
					val templates = loadTemplates(canonicalArchive)
					try {
						templates.mapIndexed { index, template ->
							val sourceId = UUID.randomUUID().toString()
							locators[sourceId] = TemplateLocator(canonicalArchive, fingerprint, index)
							TemplateSessionDescriptor(
								sourceId = sourceId,
								displayName = template.templateNameStr,
							)
						}
					} finally {
						// Catalog construction never hands these mutable objects to a caller.
						templates.forEach(ProjectTemplate::release)
					}
				}
		}

	override fun create(sourceId: String): ProjectTemplate? {
		val locator = synchronized(lock) { locators[sourceId] } ?: return null
		if (!locator.matchesCurrentArchive()) {
			return null
		}

		val freshTemplates = loadTemplates(locator.archive)
		val selected = freshTemplates.getOrNull(locator.templateIndex)
		freshTemplates.forEachIndexed { index, template ->
			if (index != locator.templateIndex) {
				template.release()
			}
		}
		return selected
	}

	private fun loadTemplates(archive: File): List<ProjectTemplate> {
		val warnings = mutableListOf<TemplateWarning>()
		return ZipTemplateReader.read(archive, warnings) { json, params, basePath, data, module ->
			ZipRecipeExecutor(
				zipProvider = { ZipFile(archive) },
				metaJson = json,
				params = params,
				basePath = basePath,
				data = data,
				defModule = module,
			)
		}
	}

	private data class TemplateLocator(
		val archive: File,
		val fingerprint: ArchiveFingerprint,
		val templateIndex: Int,
	) {
		fun matchesCurrentArchive(): Boolean = archive.isFile && ArchiveFingerprint.from(archive) == fingerprint
	}

	private data class ArchiveFingerprint(
		val size: Long,
		val lastModified: Long,
	) {
		companion object {
			fun from(file: File) = ArchiveFingerprint(file.length(), file.lastModified())
		}
	}

	companion object {
		/** Creates a factory backed by the supplied template archive directory. */
		fun fromDirectory(directory: File): ArchiveTemplateSessionFactory =
			ArchiveTemplateSessionFactory { directory.listFiles()?.toList().orEmpty() }
	}
}
