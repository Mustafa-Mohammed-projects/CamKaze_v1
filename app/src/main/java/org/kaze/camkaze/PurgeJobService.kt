package org.kaze.camkaze

import android.app.job.JobParameters
import android.app.job.JobService
import org.kaze.camkaze.data.ShareCache

/** Fires ~15 minutes after a share, even if the app was closed, and shreds the shared copies. */
class PurgeJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        ShareCache.purge(this, ShareCache.MAX_AGE_MS)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
