package com.itsaky.androidide.ai.providers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class ProviderEndpointPolicyTest {
	@Test
	fun allowsOnlyProviderHttpsEndpointsOnTheDefaultPort() {
		assertTrue(ProviderEndpointPolicy.isAllowed(URL("https://generativelanguage.googleapis.com/v1beta/models")))
		assertTrue(ProviderEndpointPolicy.isAllowed(URL("https://api.deepseek.com/chat/completions")))
		assertTrue(ProviderEndpointPolicy.isAllowed(URL("https://api.deepseek.com:443/models")))
	}

	@Test
	fun rejectsOtherSchemesHostsPortsAndUserInfo() {
		assertFalse(ProviderEndpointPolicy.isAllowed(URL("http://api.deepseek.com/models")))
		assertFalse(ProviderEndpointPolicy.isAllowed(URL("https://api.deepseek.com:8443/models")))
		assertFalse(ProviderEndpointPolicy.isAllowed(URL("https://example.com/models")))
		assertFalse(ProviderEndpointPolicy.isAllowed(URL("https://api.deepseek.com@example.com/models")))
	}
}
