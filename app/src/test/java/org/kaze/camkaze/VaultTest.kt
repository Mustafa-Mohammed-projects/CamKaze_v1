package org.kaze.camkaze

import org.junit.Assert.*
import org.junit.Test
import org.kaze.camkaze.crypto.UnlockResult
import org.kaze.camkaze.crypto.Vault
import org.kaze.camkaze.crypto.VaultException
import org.kaze.camkaze.crypto.VaultLockedException
import org.kaze.camkaze.data.Meta
import org.kaze.camkaze.data.Payload

class VaultTest {
    @Test fun roundTripAndFormat() {
        val v = newVault()
        v.create(PW.toCharArray(), true)
        val data = ByteArray(5000) { it.toByte() }
        val blob = v.encrypt(data)
        assertEquals("KAZE", String(blob.copyOfRange(0, 4)))
        assertEquals(2, blob[4].toInt())
        assertArrayEquals(data, v.decrypt(blob))
        assertFalse(blob.contentEquals(v.encrypt(data)))
    }

    @Test fun weakPasswordRejected() {
        assertThrows(IllegalArgumentException::class.java) { newVault().create("short-pass".toCharArray(), true) }
    }

    @Test fun passwordNeverStored() {
        val w = SoftwareWrapper()
        val cfg = java.io.File.createTempFile("vault", ".cfg").also { it.delete() }
        val v = Vault(cfg, w, 1000)
        v.create(PW.toCharArray(), true)
        assertFalse(cfg.readText().contains(PW))
    }

    @Test fun wrongPasswordCountsAndRightOneResets() {
        val v = newVault()
        v.create(PW.toCharArray(), true)
        v.lock()
        val r = v.unlock("wrong password here".toCharArray())
        assertEquals(UnlockResult.Wrong(1, 29), r)
        assertFalse(v.isUnlocked)
        assertEquals(UnlockResult.Success, v.unlock(PW.toCharArray()))
        assertEquals(0, v.failedAttempts)
    }

    @Test fun throttleAfterFiveFailures() {
        var now = 1_000_000L
        val v = newVault({ now })
        v.create(PW.toCharArray(), false)
        v.lock()
        repeat(5) { assertTrue(v.unlock("nope nope nope nope".toCharArray()) is UnlockResult.Wrong) }
        val t = v.unlock(PW.toCharArray())
        assertTrue(t is UnlockResult.Throttled)           // even the RIGHT password waits
        assertEquals(30_000L, (t as UnlockResult.Throttled).waitMs)
        now += 31_000
        assertEquals(UnlockResult.Success, v.unlock(PW.toCharArray()))
    }

    @Test fun delayGrowsAndIsCapped() {
        assertEquals(0L, Vault.delayForFailures(4))
        assertEquals(30_000L, Vault.delayForFailures(5))
        assertEquals(60_000L, Vault.delayForFailures(6))
        assertEquals(3_600_000L, Vault.delayForFailures(29))
    }

    @Test fun wipesAfter30FailuresWhenEnabled() {
        var now = 1_000_000L
        val v = newVault({ now })
        var wiped = 0
        v.onWipe = { wiped++ }
        v.create(PW.toCharArray(), true)
        v.lock()
        var last: UnlockResult? = null
        repeat(30) { now += 2 * 3_600_000L; last = v.unlock("nope nope nope nope".toCharArray()) }
        assertEquals(UnlockResult.Wiped, last)
        assertEquals(1, wiped)
        assertFalse(v.isConfigured)
    }

    @Test fun neverWipesWhenDisabled() {
        var now = 1_000_000L
        val v = newVault({ now })
        var wiped = 0
        v.onWipe = { wiped++ }
        v.create(PW.toCharArray(), false)
        v.lock()
        repeat(35) { now += 2 * 3_600_000L; assertTrue(v.unlock("nope nope nope nope".toCharArray()) is UnlockResult.Wrong) }
        assertEquals(0, wiped)
        assertTrue(v.isConfigured)
        now += 2 * 3_600_000L
        assertEquals(UnlockResult.Success, v.unlock(PW.toCharArray()))
    }

    @Test fun deviceKeyLostIsReportedAndNotCounted() {
        val w = SoftwareWrapper()
        val v = newVault(wrapper = w)
        v.create(PW.toCharArray(), true)
        v.lock()
        w.destroy()                                      // simulate Keystore key gone / copied to another phone
        assertEquals(UnlockResult.DeviceKeyLost, v.unlock(PW.toCharArray()))
        assertEquals(0, v.failedAttempts)
    }

    @Test fun lockZeroesKeyAndBlocks() {
        val v = newVault()
        v.create(PW.toCharArray(), true)
        v.lock()
        assertFalse(v.isUnlocked)
        assertThrows(VaultLockedException::class.java) { v.encrypt(byteArrayOf(1)) }
    }

    @Test fun holdDefersTheLockUntilReleased() {
        val v = newVault()
        v.create(PW.toCharArray(), true)
        v.hold()
        v.lock()
        assertFalse(v.isUnlocked)                        // UI sees "locked"...
        assertNotNull(v.encrypt(byteArrayOf(1, 2, 3)))   // ...but the running job can still finish
        v.release()
        assertThrows(VaultLockedException::class.java) { v.encrypt(byteArrayOf(1)) }
    }

    @Test fun tamperDetected() {
        val v = newVault()
        v.create(PW.toCharArray(), true)
        val blob = v.encrypt("secret".toByteArray())
        for (pos in intArrayOf(6, 20, 40, blob.size - 1)) {
            val bad = blob.copyOf().also { it[pos] = (it[pos].toInt() xor 1).toByte() }
            assertThrows(VaultException::class.java) { v.decrypt(bad) }
        }
        assertThrows(VaultException::class.java) { v.decrypt(ByteArray(10)) }
    }

    @Test fun eachFileGetsItsOwnKey() {
        val v = newVault()
        v.create(PW.toCharArray(), true)
        val a = v.fileKey(ByteArray(16) { 1 })
        val b = v.fileKey(ByteArray(16) { 2 })
        assertFalse(a.contentEquals(b))
    }

    @Test fun payloadHoldsMetadataInsideTheEncryption() {
        val meta = Meta(Payload.KIND_VIDEO, 90, 1_700_000_000_123L, 4567L)
        val p = Payload.pack(meta, byteArrayOf(9, 8, 7))
        assertEquals(meta, Payload.meta(p))
        assertArrayEquals(byteArrayOf(9, 8, 7), p.copyOfRange(Payload.HEADER, p.size))
    }
}
