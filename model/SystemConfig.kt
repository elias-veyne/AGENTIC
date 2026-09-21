package com.jarves.mh.model

/**
 * Global system configuration for agent behavior.
 * Used to tune heartbeat intervals, timeouts, and security settings.
 */
data class SystemConfig(
    // Heartbeat settings for multi-agent health checks
    val heartbeatIntervalSeconds: Long = 30,
    val heartbeatFailuresBeforeAlert: Int = 2,
    
    // Retry limits to prevent cost runaway
    val maxTaskRetries: Int = 3,
    val retryBaseDelayMs: Long = 1000,
    val maxRetryDelayMs: Long = 30000,
    
    // Authentication for internal agent-to-agent messages
    val internalAuthSharedToken: String = ""
)

/**
 * Default configuration. Can be persisted in app preferences.
 */
object DefaultSystemConfig {
    val default = SystemConfig()
}
