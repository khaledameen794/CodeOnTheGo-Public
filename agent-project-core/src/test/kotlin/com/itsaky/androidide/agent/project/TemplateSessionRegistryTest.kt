package com.itsaky.androidide.agent.project

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateSessionRegistryTest {
	@Test
	fun issuesOpaqueTokenAndCreatesFreshResourceOnlyOnce() {
		val factory = RecordingFactory()
		val registry = TemplateSessionRegistry(factory)
		val option = registry.issue(listOf(descriptor("archive-a:0"))).single()

		assertTrue(option.token.isNotBlank())
		assertTrue(option.token != "archive-a:0")
		assertEquals(
			TemplateSessionAcquireResult.Acquired("fresh:archive-a:0"),
			registry.consume(option.token),
		)
		assertEquals(listOf("archive-a:0"), factory.createdSourceIds)
		assertEquals(
			TemplateSessionAcquireResult.UnknownOrConsumedToken,
			registry.consume(option.token),
		)
	}

	@Test
	fun expiresTokenBeforeFactoryCanCreateTemplate() {
		val clock = MutableClock(Instant.parse("2026-08-24T00:00:00Z"))
		val factory = RecordingFactory()
		val registry = TemplateSessionRegistry(factory, clock, Duration.ofSeconds(30))
		val option = registry.issue(listOf(descriptor("archive-a:0"))).single()
		clock.advance(Duration.ofSeconds(30))

		assertEquals(TemplateSessionAcquireResult.ExpiredToken, registry.consume(option.token))
		assertTrue(factory.createdSourceIds.isEmpty())
	}

	@Test
	fun doesNotExposeFactoryFailureAsASuccessfulSession() {
		val registry = TemplateSessionRegistry<String>(IsolatedTemplateSessionFactory { null })
		val option = registry.issue(listOf(descriptor("missing:0"))).single()

		assertEquals(TemplateSessionAcquireResult.SourceUnavailable, registry.consume(option.token))
	}

	private fun descriptor(sourceId: String) =
		TemplateSessionDescriptor(
			sourceId = sourceId,
			displayName = "Empty Android project",
		)

	private class RecordingFactory : IsolatedTemplateSessionFactory<String> {
		val createdSourceIds = mutableListOf<String>()

		override fun create(sourceId: String): String {
			createdSourceIds += sourceId
			return "fresh:$sourceId"
		}
	}

	private class MutableClock(initial: Instant) : Clock() {
		private var current = initial

		override fun getZone() = ZoneOffset.UTC

		override fun withZone(zone: java.time.ZoneId): Clock = this

		override fun instant(): Instant = current

		fun advance(duration: Duration) {
			current = current.plus(duration)
		}
	}
}
