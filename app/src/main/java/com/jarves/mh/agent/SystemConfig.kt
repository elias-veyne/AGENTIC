package com.jarves.mh.agent

data class SystemConfig(
    val heartbeatIntervalMs: Long = 30000,
    val heartbeatFailuresThreshold: Int = 3,
    val maxTaskRetries: Int = 3,
    val retryBaseDelayMs: Long = 1000,
    val maxRetryDelayMs: Long = 30000,
    val internalAuthSharedToken: String = ""
)
