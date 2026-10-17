package io.github.yunuserdemsihhatli.homecast.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestLedgerTest {
    private val ledger = RequestLedger(capacity = 3)
    private val media = "https://cdn.example/v/master"

    @Test fun mainFrameRequestConfirmsAnyMessage() {
        ledger.record(media, isMainFrame = true, referer = null)

        assertTrue(ledger.confirms(media, "https://anything.example"))
    }

    @Test fun refererOriginMustMatchTheSendingOrigin() {
        ledger.record(media, isMainFrame = false, referer = "https://player.example/embed/1")

        assertTrue(ledger.confirms("$media#x", "https://player.example"))
        assertFalse(ledger.confirms(media, "https://ads.example"))
        assertFalse(ledger.confirms(media, "null"))
        assertFalse(ledger.confirms(media, null))
    }

    @Test fun urlNeverRequestedIsNotConfirmed() {
        ledger.record("https://cdn.example/other", isMainFrame = false, referer = "https://player.example/")

        assertFalse(ledger.confirms(media, "https://player.example"))
    }

    @Test fun refererlessRequestMayOnlyBeUpgradedFromTheTopPageOrigin() {
        ledger.record(media, isMainFrame = false, referer = null)

        assertTrue(ledger.mayUpgradeHeaders(media, "https://site.example", "https://site.example/watch/1"))
        assertFalse(ledger.mayUpgradeHeaders(media, "https://ads.example", "https://site.example/watch/1"))
        assertFalse(ledger.confirms(media, "https://site.example"))
    }

    @Test fun requestWithRefererIsUpgradedOnlyByThatOrigin() {
        ledger.record(media, isMainFrame = false, referer = "https://player.example/embed/1")

        assertTrue(ledger.mayUpgradeHeaders(media, "https://player.example", "https://site.example/watch/1"))
        assertFalse(ledger.mayUpgradeHeaders(media, "https://site.example", "https://site.example/watch/1"))
    }

    @Test fun clearForgetsPreviousPageRequests() {
        ledger.record(media, isMainFrame = false, referer = "https://player.example/")

        ledger.clear()

        assertFalse(ledger.confirms(media, "https://player.example"))
    }

    @Test fun oldestRequestsAreEvicted() {
        ledger.record(media, isMainFrame = false, referer = "https://player.example/")
        repeat(
            3,
        ) { ledger.record("https://cdn.example/seg$it.ts", isMainFrame = false, referer = "https://player.example/") }

        assertFalse(ledger.confirms(media, "https://player.example"))
    }
}
