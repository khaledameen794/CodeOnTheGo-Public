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

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * A trusted, adapter-owned reference to a template source. Its [sourceId] is
 * never supplied by a model: the adapter derives it while enumerating the
 * installed template catalog.
 */
data class TemplateSessionDescriptor(
	val sourceId: String,
	val displayName: String,
	val description: String? = null,
)

/** A user-visible template choice whose token is valid only in this registry. */
data class TemplateSessionOption(
	val token: String,
	val displayName: String,
	val description: String?,
	val expiresAt: Instant,
)

/**
 * Creates a fresh template instance for a previously enumerated source id.
 *
 * Implementations must never return a mutable template instance shared with a
 * visible template picker or a different agent run.
 */
fun interface IsolatedTemplateSessionFactory<T> {
	fun create(sourceId: String): T?
}

sealed interface TemplateSessionAcquireResult<out T> {
	data class Acquired<T>(val template: T) : TemplateSessionAcquireResult<T>

	data object UnknownOrConsumedToken : TemplateSessionAcquireResult<Nothing>

	data object ExpiredToken : TemplateSessionAcquireResult<Nothing>

	data object SourceUnavailable : TemplateSessionAcquireResult<Nothing>
}

/**
 * Issues opaque, short-lived, one-time template tokens for one agent run.
 *
 * This registry deliberately stores only immutable [TemplateSessionDescriptor]
 * data. The mutable Android [com.itsaky.androidide.templates.Template] object
 * is created only when a token is consumed, by the platform adapter.
 */
class TemplateSessionRegistry<T>(
	private val factory: IsolatedTemplateSessionFactory<T>,
	private val clock: Clock = Clock.systemUTC(),
	private val tokenTtl: Duration = DEFAULT_TOKEN_TTL,
) {
	init {
		require(!tokenTtl.isNegative && !tokenTtl.isZero) { "tokenTtl must be positive" }
	}

	private val lock = Any()
	private val pending = mutableMapOf<String, PendingSession>()

	fun issue(descriptors: Collection<TemplateSessionDescriptor>): List<TemplateSessionOption> {
		val issuedAt = clock.instant()
		val expiresAt = issuedAt.plus(tokenTtl)
		return synchronized(lock) {
			pruneExpired(issuedAt)
			descriptors.map { descriptor ->
				val token = UUID.randomUUID().toString()
				pending[token] = PendingSession(descriptor, expiresAt)
				TemplateSessionOption(
					token = token,
					displayName = descriptor.displayName,
					description = descriptor.description,
					expiresAt = expiresAt,
				)
			}
		}
	}

	fun consume(token: String): TemplateSessionAcquireResult<T> {
		if (token.isBlank()) {
			return TemplateSessionAcquireResult.UnknownOrConsumedToken
		}

		val session =
			synchronized(lock) {
				val now = clock.instant()
				val found = pending.remove(token) ?: return TemplateSessionAcquireResult.UnknownOrConsumedToken
				pruneExpired(now)
				if (!now.isBefore(found.expiresAt)) {
					return TemplateSessionAcquireResult.ExpiredToken
				}
				found
			}

		return try {
			factory.create(session.descriptor.sourceId)?.let { template ->
				TemplateSessionAcquireResult.Acquired(template)
			}
				?: TemplateSessionAcquireResult.SourceUnavailable
		} catch (_: Exception) {
			TemplateSessionAcquireResult.SourceUnavailable
		}
	}

	private fun pruneExpired(now: Instant) {
		pending.entries.removeIf { (_, session) -> !now.isBefore(session.expiresAt) }
	}

	private data class PendingSession(
		val descriptor: TemplateSessionDescriptor,
		val expiresAt: Instant,
	)

	private companion object {
		val DEFAULT_TOKEN_TTL: Duration = Duration.ofMinutes(5)
	}
}
