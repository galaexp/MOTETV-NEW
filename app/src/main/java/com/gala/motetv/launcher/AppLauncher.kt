package com.gala.motetv.launcher

import com.gala.motetv.core.logging.TvLogger
import com.gala.motetv.remote.RemoteState
import com.gala.motetv.remote.TvRemoteManager

object AppLauncher {

    /**
     * Attempts to launch an app on the TV using the prioritized strategy.
     *
     * @param app The app definition to launch.
     * @param remoteManager The remote manager managing the connection.
     * @param strategyIndex Specific strategy index to try, defaults to 0 (primary deep link).
     * @return LaunchResult indicating success or failure.
     */
    fun launch(
        app: AppDefinition,
        remoteManager: TvRemoteManager,
        strategyIndex: Int = 0
    ): LaunchResult {
        if (remoteManager.remoteState.value != RemoteState.READY) {
            TvLogger.w(
                TvLogger.TAG_REMOTE,
                "[APP_LAUNCH_FAILURE] Cannot launch ${app.name}: Remote connection state is ${remoteManager.remoteState.value}"
            )
            return LaunchResult.Failure(
                appName = app.name,
                reason = "Remote is not connected to TV (Current: ${remoteManager.remoteState.value})"
            )
        }

        val allUris = app.getAllLaunchUris()
        if (allUris.isEmpty()) {
            TvLogger.w(TvLogger.TAG_REMOTE, "[APP_LAUNCH_FAILURE] ${app.name} has no launch URIs configured")
            return LaunchResult.Failure(appName = app.name, reason = "No launch URI available")
        }

        val safeIndex = strategyIndex.coerceIn(0, allUris.lastIndex)
        val targetUri = allUris[safeIndex]

        val method = when {
            targetUri.startsWith("market://") -> LaunchMethod.PROTOCOL_PACKAGE_LAUNCH
            safeIndex == 0 -> LaunchMethod.PROTOCOL_DEEP_LINK
            else -> LaunchMethod.FALLBACK_DEEP_LINK
        }

        TvLogger.i(TvLogger.TAG_REMOTE, "[APP_LAUNCH] App: ${app.name}")
        TvLogger.i(TvLogger.TAG_REMOTE, "[APP_LAUNCH_METHOD] Method: $method, Uri: $targetUri (Strategy $safeIndex of ${allUris.size})")

        return try {
            remoteManager.launchAppLink(targetUri)
            TvLogger.i(TvLogger.TAG_REMOTE, "[APP_LAUNCH_SUCCESS] Launch message sent for ${app.name} using $targetUri")
            LaunchResult.Success(
                appName = app.name,
                uriUsed = targetUri,
                method = method
            )
        } catch (e: Exception) {
            TvLogger.e(TvLogger.TAG_REMOTE, "[APP_LAUNCH_FAILURE] Failed to send launch for ${app.name}: ${e.message}", e)
            LaunchResult.Failure(
                appName = app.name,
                reason = e.message ?: "Failed to dispatch launch command",
                error = e
            )
        }
    }

    /**
     * Returns human-readable descriptions of all strategies for an app.
     */
    fun getStrategyDescriptions(app: AppDefinition): List<String> {
        return app.getAllLaunchUris().mapIndexed { index, uri ->
            when {
                index == 0 -> "Primary Deep Link: $uri"
                uri.startsWith("market://") -> "Package Launch: $uri"
                else -> "Fallback Scheme: $uri"
            }
        }
    }
}
