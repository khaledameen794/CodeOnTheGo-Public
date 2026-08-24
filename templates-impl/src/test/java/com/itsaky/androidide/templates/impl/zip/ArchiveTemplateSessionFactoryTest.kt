package com.itsaky.androidide.templates.impl.zip

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.utils.Environment
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
	fun `archive change invalidates catalog source before template creation`() {
		val archive = createArchive()
		val factory = ArchiveTemplateSessionFactory.fromDirectory(archive.parentFile)
		val descriptor = factory.catalog().single()

		archive.appendText("changed")

		assertThat(factory.create(descriptor.sourceId)).isNull()
	}

	private fun createArchive(): File {
		val archive = File(temporaryFolder.newFolder("archives"), "sample.cgt")
		ZipOutputStream(archive.outputStream()).use { output ->
			writeEntry(
				output,
				"templates.json",
				"""{"templates":[{"path":"sample"}]}""",
			)
			writeEntry(
				output,
				"sample/template/template.json",
				"""{"name":"Isolated template","description":null,"version":"1"}""",
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
