package app.githubble.mobile.network

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Re-establishes the user's persisted VLESS route after process recreation or foreground return.
 *
 * InAppVlessRouter already stores the desired enabled/profile state. Xray itself is process-local,
 * so Android kills it together with the app process. This restorer treats the persisted switch as
 * authoritative and retries startup until the loopback listeners are available or the user turns
 * the profile off.
 */
object InAppVlessRouteRestorer {
    private const val PREFS = "in_app_vless_router_v1"
    private const val KEY_ACTIVE = "active"
    private const val KEY_ENABLED = "enabled"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile private var app: Application? = null
    @Volatile private var restoreJob: Job? = null
    @Volatile private var callbacksRegistered = false
    private var startedActivities = 0

    fun start(application: Application) {
        app = application
        registerForegroundCallback(application)
        scheduleRestore(initialDelayMs = 120L)
    }

    private fun registerForegroundCallback(application: Application) {
        synchronized(lock) {
            if (callbacksRegistered) return
            callbacksRegistered = true
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    val shouldRestore = synchronized(lock) {
                        val enteringForeground = startedActivities == 0
                        startedActivities++
                        enteringForeground
                    }
                    if (shouldRestore) scheduleRestore(initialDelayMs = 160L)
                }

                override fun onActivityStopped(activity: Activity) {
                    synchronized(lock) { startedActivities = (startedActivities - 1).coerceAtLeast(0) }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            })
        }
    }

    private fun scheduleRestore(initialDelayMs: Long) {
        val application = app ?: return
        synchronized(lock) {
            restoreJob?.cancel()
            restoreJob = scope.launch {
                if (initialDelayMs > 0) delay(initialDelayMs)
                restoreUntilConnected(application)
            }
        }
    }

    private suspend fun restoreUntilConnected(application: Application) {
        val prefs = application.getSharedPreferences(PREFS, Application.MODE_PRIVATE)
        val backoff = longArrayOf(0L, 250L, 600L, 1_200L, 2_400L, 4_000L)

        for (waitMs in backoff) {
            if (waitMs > 0) delay(waitMs)
            if (!prefs.getBoolean(KEY_ENABLED, false)) return

            val activeId = prefs.getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() } ?: return
            val profileExists = runCatching {
                InAppVlessRouter.profiles().any { it.id == activeId && it.enabled }
            }.getOrDefault(false)
            if (!profileExists) return

            val current = InAppVlessRouter.status()
            if (current.enabled && current.activeProfileId == activeId && current.localPort != null) return

            runCatching { InAppVlessRouter.enable(activeId) }
                .onSuccess {
                    val restored = InAppVlessRouter.status()
                    if (restored.enabled && restored.activeProfileId == activeId) return
                }
        }
    }
}

