package com.itsaky.androidide.templates.impl.zip

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.utils.Environment
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class ArchiveTemplateSessionFactoryTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	@Before
	fun setUp() {
		Environment.PROJECTS_DIR = temporaryFolder.newFolder("projects")
	}

	@Test
	fun `catalog source creates a distinct template instance for each session`() {
		val archive = createArchive()
		val factory = ArchiveTemplateSessionFactory.fromDirectory(archive.parentFile)
		val descriptor = factory.catalog().single()

		val first = factory.create(descriptor.sourceId)
		val second = factory.create(descriptor.sourceId)

		assertThat(first).isNotNull()
		assertThat(second).isNotNull()
		assertThat(first).isNotSameInstanceAs(second)
		assertThat(first!!.templateNameStr).isEqualTo("Isolated template")
		first.release()
		second!!.release()
	}

	@Test
	fun `catalog and isolated template preview do not create a custom save location`() {
		val customSaveLocation = File(temporaryFolder.root, "not-created-until-approved")
		val archive = createArchive(customSaveLocation.absolutePath)
		val factory = ArchiveTemplateSessionFactory.fromDirectory(archive.parentFile)
		val descriptor = factory.catalog().single()

		assertThat(customSaveLocation.exists()).isFalse()
		val template = factory.create(descriptor.sourceId)
		assertThat(template).isNotNull()
		assertThat(customSaveLocation.exists()).isFalse()
		template!!.release()
	}

	@Test
	fun `archive change invalidates catalog source before template creation`() {
		val archive = createArchive()
		val factory = ArchiveTemplateSessionFactory.fromDirectory(archive.parentFile)
		val descriptor = factory.catalog().single()

		archive.appendText("changed")

		assertThat(factory.create(descriptor.sourceId)).isNull()
	}

	@Test
	fun `same size and mtime content change invalidates catalog source`() {
		val archive = createArchive(archiveComment = "same-size-comment-A")
		val factory = ArchiveTemplateSessionFactory.fromDirectory(archive.parentFile)
		val descriptor = factory.catalog().single()
		val originalSize = archive.length()
		val originalLastModified = archive.lastModified()

		RandomAccessFile(archive, "rw").use { file ->
			file.seek(originalSize - 1)
			file.write('B'.code)
		}
		assertThat(archive.length()).isEqualTo(originalSize)
		assertThat(archive.setLastModified(originalLastModified)).isTrue()
		assertThat(archive.lastModified()).isEqualTo(originalLastModified)

		assertThat(factory.create(descriptor.sourceId)).isNull()
	}

	private fun createArchive(
		defaultSaveLocation: String? = null,
		archiveComment: String? = null,
	): File {
		val archive = File(temporaryFolder.newFolder("archives"), "sample.cgt")
		ZipOutputStream(archive.outputStream()).use { output ->
			archiveComment?.let(output::setComment)
			writeEntry(
				output,
				"templates.json",
				"""{"templates":[{"path":"sample"}]}""",
			)
			writeEntry(
				output,
				"sample/template/template.json",
				"""{"name":"Isolated template","description":null,"version":"1","defaultSaveLocation":${Gson().toJson(
					defaultSaveLocation,
				)}}""",
			)
		}
		return archive
	}

	private fun writeEntry(
		output: ZipOutputStream,
		name: String,
		content: String,
	) {
		output.putNextEntry(ZipEntry(name))
		output.write(content.toByteArray())
		output.closeEntry()
	}
}
