package io.github.yunuserdemsihhatli.homecast.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTrustTest {
    private val page = "https://site.example/watch/1"

    @Test fun reportTiedToASeenRequestIsTrusted() {
        assertTrue(ReportTrust.isTrusted(confirmedByLedger = true, sourceOrigin = "https://player.other", page))
    }

    @Test fun reportFromTheMainPagesOriginIsTrusted() {
        assertTrue(ReportTrust.isTrusted(confirmedByLedger = false, sourceOrigin = "https://site.example", page))
    }

    @Test fun unconfirmedReportFromAnotherOriginIsNot() {
        assertFalse(ReportTrust.isTrusted(confirmedByLedger = false, sourceOrigin = "https://ads.other", page))
        assertFalse(ReportTrust.isTrusted(confirmedByLedger = false, sourceOrigin = "http://site.example", page))
        assertFalse(ReportTrust.isTrusted(confirmedByLedger = false, sourceOrigin = "https://site.example:8443", page))
    }

    @Test fun unknownOriginOrPageIsNotTrusted() {
        assertFalse(ReportTrust.isTrusted(confirmedByLedger = false, sourceOrigin = null, page))
        assertFalse(ReportTrust.isTrusted(confirmedByLedger = false, sourceOrigin = "null", page))
        assertFalse(ReportTrust.isTrusted(confirmedByLedger = false, sourceOrigin = "https://site.example", null))
    }
}
