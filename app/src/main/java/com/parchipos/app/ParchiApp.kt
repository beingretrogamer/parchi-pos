package com.parchipos.app

import android.app.Activity
import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/**
 * Keeps Parchi Cloud sync going while the app is in use: every 10 seconds
 * while any Parchi screen is on show, and once more when the last one leaves.
 * If bills are still waiting then, a job sends them when there is network,
 * even with the app closed.
 */
class ParchiApp : Application(), Application.ActivityLifecycleCallbacks {

    private val ui = Handler(Looper.getMainLooper())
    private var started = 0
    private val tick = object : Runnable {
        override fun run() {
            SyncEngine.kick(this@ParchiApp)
            ui.postDelayed(this, EVERY_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        if (started++ == 0) {
            ui.removeCallbacks(tick)
            tick.run()
        }
    }

    override fun onActivityStopped(activity: Activity) {
        if (--started > 0) return
        // A manager's unlock ends when Parchi leaves the screen.
        Manager.lock()
        ui.removeCallbacks(tick)
        SyncEngine.kick(this)
        if (Cloud.connected(this) && Store.outboxCount(this) > 0) SyncJob.schedule(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    companion object {
        const val EVERY_MS = 10_000L
    }
}

/** Sends waiting bills once the phone has network, with the app closed. */
class SyncJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "parchi-sync-job") {
            val err = SyncEngine.runOnce(applicationContext)
            // Offline again, or still waiting: let the scheduler try later.
            jobFinished(params, err.isNotEmpty() && Store.outboxCount(applicationContext) > 0)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val ID = 7001

        fun schedule(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java) ?: return
            js.schedule(JobInfo.Builder(ID, ComponentName(c, SyncJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build())
        }
    }
}
