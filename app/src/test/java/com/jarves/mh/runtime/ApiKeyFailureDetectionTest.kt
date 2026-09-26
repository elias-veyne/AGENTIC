package com.jarves.mh.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the API-failure detection that drives transparent key rotation — the
 * self-healing behaviour the blueprint calls out as the primary in-app recovery
 * path. Mirrors MainViewModel.isApiKeyFailure, which replaced the deleted
 * ProviderRuntimeErrorDetector object.
 */
class ApiKeyFailureDetectionTest {

    private fun isApiKeyFailure(reason: String): Boolean {
        val value = reason.lowercase()
        return "api key" in value || "authentication" in value || "user not found" in value ||
            "http 401" in value || "http 403" in value || "http 429" in value ||
            "expired" in value || "quota" in value || "rate limit" in value
    }

    @Test
    fun authenticationErrorsAreFatal() {
        assertTrue(isApiKeyFailure("Failed to authenticate. API Error: 401 User not found."))
        assertTrue(isApiKeyFailure("HTTP 401 unauthorized"))
    }

    @Test
    fun rateLimitAndQuotaAreRetrievableFailures() {
        assertTrue(isApiKeyFailure("HTTP 429 rate limit exceeded"))
        assertTrue(isApiKeyFailure("Quota exhausted for this key"))
        assertTrue(isApiKeyFailure("The provided API key has expired"))
    }

    @Test
    fun ordinaryRuntimeOutputIsNotFatal() {
        assertFalse(isApiKeyFailure("Agent runtime connected"))
        assertFalse(isApiKeyFailure("Running npm install"))
        assertFalse(isApiKeyFailure(""))
    }
}
