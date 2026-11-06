package com.typezero.resound.core.ffmpeg

import org.junit.Assert.*
import org.junit.Test

class CancelGateTest {
    @Test fun cancelFirstThenPublishReleasesHandleOnce() {
        val g = CancelGate(); assertNull(g.request()); assertEquals(9L, g.publish(9L)); assertNull(g.request())
    }
    @Test fun publishFirstThenCancelReleasesOnce() {
        val g = CancelGate(); assertNull(g.publish(9L)); assertEquals(9L, g.request()); assertNull(g.request())
    }
    @Test fun zeroIsAValidHandleNotAPlaceholder() {
        val g = CancelGate(); g.request(); assertEquals(0L, g.publish(0L))
    }
    @Test fun terminalSuppressesDispatch() {
        val g = CancelGate(); g.terminal(); g.request(); assertNull(g.publish(5L))
    }
    @Test fun noCancelRequestedNoDispatch() { assertNull(CancelGate().publish(3L)) }
}
