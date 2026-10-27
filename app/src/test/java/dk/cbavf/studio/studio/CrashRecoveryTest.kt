package dk.cbavf.studio.studio

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CrashRecoveryTest {
    @Test fun aCrashAndAnAnrGetABanner() {
        assertNotNull(CrashRecovery.banner(ApplicationExitInfo.REASON_CRASH, 1000, 0))
        assertNotNull(CrashRecovery.banner(ApplicationExitInfo.REASON_CRASH_NATIVE, 1000, 0))
        assertNotNull(CrashRecovery.banner(ApplicationExitInfo.REASON_ANR, 1000, 0))
        assertNotNull(CrashRecovery.banner(ApplicationExitInfo.REASON_LOW_MEMORY, 1000, 0))
    }

    @Test fun anOrdinaryExitGetsNone() {
        assertNull(CrashRecovery.banner(ApplicationExitInfo.REASON_USER_REQUESTED, 1000, 0))
        assertNull(CrashRecovery.banner(ApplicationExitInfo.REASON_EXIT_SELF, 1000, 0))
        assertNull(CrashRecovery.banner(ApplicationExitInfo.REASON_USER_STOPPED, 1000, 0))
    }

    @Test fun aDismissedExitDoesNotComeBack() {
        assertNull(CrashRecovery.banner(ApplicationExitInfo.REASON_CRASH, 1000, 1000))
        assertNull(CrashRecovery.banner(ApplicationExitInfo.REASON_CRASH, 1000, 5000))
        // a later crash is a new one
        assertNotNull(CrashRecovery.banner(ApplicationExitInfo.REASON_CRASH, 6000, 5000))
    }

    @Test fun theWordingNamesWhatHappened() {
        assertEquals("crashed", CrashRecovery.describe(ApplicationExitInfo.REASON_CRASH))
        assertEquals("stopped responding", CrashRecovery.describe(ApplicationExitInfo.REASON_ANR))
    }
}
