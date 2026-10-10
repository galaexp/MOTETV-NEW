package com.gala.motetv.launcher

enum class LaunchMethod {
    PROTOCOL_DEEP_LINK,
    PROTOCOL_PACKAGE_LAUNCH,
    FALLBACK_DEEP_LINK
}

sealed class LaunchResult {
    data class Success(
        val appName: String,
        val uriUsed: String,
        val method: LaunchMethod
    ) : LaunchResult()

    data class Failure(
        val appName: String,
        val reason: String,
        val error: Throwable? = null
    ) : LaunchResult()
}
