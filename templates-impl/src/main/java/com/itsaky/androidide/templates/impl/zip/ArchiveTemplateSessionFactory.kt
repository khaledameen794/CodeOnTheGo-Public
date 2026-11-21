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
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
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
	private val log = LoggerFactory.getLogger(ArchiveTemplateSessionFactory::class.java)
	private val lock = Any()
	private val locators = mutableMapOf<String, TemplateLocator>()

	/**
	 * Enumerates installed templates and replaces the current session catalog.
	 * Returned source IDs are opaque and are meaningful only to this factory.
	 */
	fun catalog(): List<TemplateSessionDescriptor> =
		synchronized(lock) {
			locators.clear()
			val archiveFiles =
				try {
					archives()
				} catch (error: Exception) {
					log.error("Failed to enumerate template archives", error)
					emptyList()
				}
			archiveFiles
				.filter { it.isFile && it.extension.equals(TEMPLATE_ARCHIVE_EXTENSION, ignoreCase = true) }
				.sortedBy { it.name }
				.flatMap { archive ->
					var templates = emptyList<ProjectTemplate>()
					try {
						val canonicalArchive = archive.canonicalFile
						val fingerprint = ArchiveFingerprint.from(canonicalArchive)
						templates = loadTemplates(canonicalArchive)
						val entries =
							templates.mapIndexed { index, template ->
								val sourceId = UUID.randomUUID().toString()
								sourceId to
									TemplateSessionDescriptor(
										sourceId = sourceId,
										displayName = template.templateNameStr,
									)
							}
						entries.forEachIndexed { index, (sourceId, _) ->
							locators[sourceId] = TemplateLocator(canonicalArchive, fingerprint, index)
						}
						entries.map { it.second }
					} catch (error: Exception) {
						log.error("Failed to catalog template archive: $archive", error)
						emptyList()
					} finally {
						// Catalog construction never hands these mutable objects to a caller.
						templates.forEach { template ->
							runCatching { template.release() }
								.onFailure { error -> log.warn("Failed to release catalog template", error) }
						}
					}
				}
		}

	override fun create(sourceId: String): ProjectTemplate? {
		val locator = synchronized(lock) { locators[sourceId] } ?: return null
		if (!locator.matchesCurrentArchive()) {
			return null
		}

		val freshTemplates = loadTemplates(locator.archive, locator.fingerprint)
		if (!locator.matchesCurrentArchive()) {
			freshTemplates.forEach(ProjectTemplate::release)
			return null
		}
		val selected = freshTemplates.getOrNull(locator.templateIndex)
		freshTemplates.forEachIndexed { index, template ->
			if (index != locator.templateIndex) {
				template.release()
			}
		}
		return selected
	}

	private fun loadTemplates(
		archive: File,
		expectedFingerprint: ArchiveFingerprint? = null,
	): List<ProjectTemplate> {
		val warnings = mutableListOf<TemplateWarning>()
		return ZipTemplateReader.read(archive, warnings) { json, params, basePath, data, module ->
			ZipRecipeExecutor(
				zipProvider = {
					check(expectedFingerprint == null || expectedFingerprint.matches(archive)) {
						"Template archive changed after review"
					}
					ZipFile(archive)
				},
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
		fun matchesCurrentArchive(): Boolean = fingerprint.matches(archive)
	}

	private data class ArchiveFingerprint(
		val size: Long,
		val lastModified: Long,
		val sha256: String,
	) {
		fun matches(file: File): Boolean {
			if (!file.isFile || file.length() != size || file.lastModified() != lastModified) {
				return false
			}
			return runCatching { from(file) == this }.getOrDefault(false)
		}

		companion object {
			fun from(file: File): ArchiveFingerprint {
				val size = file.length()
				val lastModified = file.lastModified()
				val digest = MessageDigest.getInstance("SHA-256")
				file.inputStream().buffered().use { input ->
					val buffer = ByteArray(8 * 1024)
					while (true) {
						val count = input.read(buffer)
						if (count < 0) break
						digest.update(buffer, 0, count)
					}
				}
				check(file.length() == size && file.lastModified() == lastModified) {
					"Template archive changed while calculating its fingerprint"
				}
				val sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
				return ArchiveFingerprint(size, lastModified, sha256)
			}
		}
	}

	companion object {
		/** Creates a factory backed by the supplied template archive directory. */
		fun fromDirectory(directory: File): ArchiveTemplateSessionFactory =
			ArchiveTemplateSessionFactory { directory.listFiles()?.toList().orEmpty() }
	}
}
