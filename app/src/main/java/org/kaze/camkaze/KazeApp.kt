package org.kaze.camkaze

import android.app.Application
import android.content.Context
import org.kaze.camkaze.crypto.KeystoreWrapper
import org.kaze.camkaze.crypto.Vault
import org.kaze.camkaze.data.SecureDelete
import org.kaze.camkaze.data.SecureRepo
import org.kaze.camkaze.data.ShareCache
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object Services {
    lateinit var vault: Vault
    lateinit var secure: SecureRepo
    /** Temp folder for secure video recording (shredded right after encryption and at app start). */
    lateinit var tmpDir: File
    /** One global worker: jobs started by the camera must survive the screen being closed. */
    val io: ExecutorService = Executors.newSingleThreadExecutor()
}

class KazeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Services.tmpDir = File(noBackupFilesDir, "rec").apply { mkdirs() }
        Services.vault = Vault(File(filesDir, "vault2.cfg"), KeystoreWrapper())
        Services.secure = SecureRepo(File(filesDir, "secure2"), Services.vault)
        Services.vault.onLock = { Services.secure.clearCache() }
        Services.vault.onWipe = {
            Services.secure.wipeAll()
            SecureDelete.shredAll(Services.tmpDir)
        }
        SecureDelete.shredAll(Services.tmpDir)   // leftovers of an interrupted recording
        ShareCache.purge(this, 0)
    }

    companion object {
        /** Data from the previous (v1) vault format; it cannot be opened by this version. */
        fun legacyExists(ctx: Context) =
            File(ctx.filesDir, "vault.cfg").exists() || File(ctx.filesDir, "secure").isDirectory

        fun purgeLegacy(ctx: Context) {
            SecureDelete.shredAll(File(ctx.filesDir, "secure"))
            File(ctx.filesDir, "secure").delete()
            SecureDelete.shred(File(ctx.filesDir, "vault.cfg"))
        }
    }
}
