package com.thatsmyface.nearby

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageDeliveryTrackerTest {
    @Test fun enqueueAndProgressDoNotCountAsDelivery() = runTest {
        val tracker = MessageDeliveryTracker()
        var enqueued = false
        var cancelled = false
        val send = async { tracker.send("friend", 1, { enqueued = true }, { cancelled = true }) }
        runCurrent()
        assertTrue(enqueued)
        assertFalse(send.isCompleted)
        assertTrue(tracker.update("friend", 1, PayloadStatus.IN_PROGRESS))
        runCurrent()
        assertFalse(send.isCompleted)
        assertTrue(tracker.update("friend", 1, PayloadStatus.SUCCESS))
        send.await()
        assertFalse(cancelled)
        assertFalse(tracker.update("friend", 1, PayloadStatus.SUCCESS))
    }

    @Test fun callbackFromAnotherPeerCannotAcknowledgeMessage() = runTest {
        val tracker = MessageDeliveryTracker()
        val send = async { tracker.send("friend", 1, {}, {}) }
        runCurrent()
        assertFalse(tracker.update("other", 1, PayloadStatus.SUCCESS))
        assertFalse(tracker.update("friend", 2, PayloadStatus.SUCCESS))
        runCurrent()
        assertFalse(send.isCompleted)
        tracker.update("friend", 1, PayloadStatus.SUCCESS)
        send.await()
    }

    @Test fun deliveryCallbackCanArriveBeforeEnqueueTaskFinishes() = runTest {
        val tracker = MessageDeliveryTracker()
        val enqueueResult = CompletableDeferred<Unit>()
        val send = async { tracker.send("friend", 1, { enqueueResult.await() }, {}) }
        runCurrent()
        tracker.update("friend", 1, PayloadStatus.SUCCESS)
        runCurrent()
        assertFalse(send.isCompleted)
        enqueueResult.complete(Unit)
        send.await()
    }

    @Test fun transmissionFailureAfterEnqueueIsReportedAndReleased() = runTest {
        val tracker = MessageDeliveryTracker()
        var cancelled = 0
        val send = async { runCatching { tracker.send("friend", 1, {}, { cancelled++ }) } }
        runCurrent()
        tracker.update("friend", 1, PayloadStatus.FAILED)
        assertTrue(send.await().exceptionOrNull() is IOException)
        assertEquals(1, cancelled)
        assertFalse(tracker.update("friend", 1, PayloadStatus.SUCCESS))
    }

    @Test fun sdkCancellationFailsDeliveryWithoutClaimingSuccess() = runTest {
        val tracker = MessageDeliveryTracker()
        val send = async { runCatching { tracker.send("friend", 1, {}, {}) } }
        runCurrent()
        tracker.update("friend", 1, PayloadStatus.CANCELLED)
        assertTrue(send.await().exceptionOrNull() is IOException)
    }

    @Test fun missingCallbackTimesOutAndCancelsThePayload() = runTest {
        val tracker = MessageDeliveryTracker(timeoutMillis = 100)
        var cancelled = false
        val send = async { runCatching { tracker.send("friend", 1, {}, { cancelled = true }) } }
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertTrue(send.await().exceptionOrNull() is IOException)
        assertTrue(cancelled)
        assertFalse(tracker.update("friend", 1, PayloadStatus.SUCCESS))
    }

    @Test fun disconnectReleasesOnlyThatPeersMessages() = runTest {
        val tracker = MessageDeliveryTracker()
        val first = async { runCatching { tracker.send("first", 1, {}, {}) } }
        val second = async { tracker.send("second", 2, {}, {}) }
        runCurrent()
        tracker.disconnect("first")
        assertTrue(first.await().exceptionOrNull() is IOException)
        assertFalse(second.isCompleted)
        tracker.update("second", 2, PayloadStatus.SUCCESS)
        second.await()
    }

    @Test fun disconnectAlsoReleasesAnUnfinishedEnqueueTask() = runTest {
        val tracker = MessageDeliveryTracker()
        val enqueueResult = CompletableDeferred<Unit>()
        var cancelled = false
        val send = async { runCatching { tracker.send("friend", 1, { enqueueResult.await() }, { cancelled = true }) } }
        runCurrent()
        tracker.disconnect("friend")
        runCurrent()
        assertTrue(send.isCompleted)
        assertTrue(send.await().exceptionOrNull() is IOException)
        assertTrue(cancelled)
        assertFalse(enqueueResult.isCompleted)
    }

    @Test fun stoppingReleasesEveryPendingMessageAndAllowsAnotherSession() = runTest {
        val tracker = MessageDeliveryTracker()
        val first = async { runCatching { tracker.send("first", 1, {}, {}) } }
        val second = async { runCatching { tracker.send("second", 2, {}, {}) } }
        runCurrent()
        tracker.clear()
        assertTrue(first.await().exceptionOrNull() is IOException)
        assertTrue(second.await().exceptionOrNull() is IOException)
        val next = async { tracker.send("first", 3, {}, {}) }
        runCurrent()
        assertFalse(tracker.update("first", 1, PayloadStatus.SUCCESS))
        tracker.update("first", 3, PayloadStatus.SUCCESS)
        next.await()
    }

    @Test fun refreshCancellationDoesNotFailAnotherMessageToTheSamePeer() = runTest {
        val tracker = MessageDeliveryTracker()
        var cancelled = false
        val oldRefresh = async { tracker.send("friend", 1, {}, { cancelled = true }) }
        val request = async { tracker.send("friend", 2, {}, {}) }
        runCurrent()
        oldRefresh.cancel()
        oldRefresh.join()
        assertTrue(oldRefresh.isCancelled)
        assertTrue(cancelled)
        assertFalse(tracker.update("friend", 1, PayloadStatus.CANCELLED))
        assertFalse(request.isCompleted)
        tracker.update("friend", 2, PayloadStatus.SUCCESS)
        request.await()
    }

    @Test fun callersTimeoutRemainsCancellationEvenIfSdkCancelThrows() = runTest {
        val tracker = MessageDeliveryTracker(timeoutMillis = 1000)
        val send = async {
            withTimeout(100) { tracker.send("friend", 1, {}, { throw SecurityException() }) }
        }
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertTrue(send.isCancelled)
        assertFalse(tracker.update("friend", 1, PayloadStatus.SUCCESS))
    }

    @Test fun enqueueFailureReleasesMessageBeforeRetry() = runTest {
        val tracker = MessageDeliveryTracker()
        var cancelled = false
        val failure = runCatching { tracker.send("friend", 1, { throw IOException("enqueue failed") }, { cancelled = true }) }
        assertTrue(failure.exceptionOrNull() is IOException)
        assertTrue(cancelled)
        assertFalse(tracker.update("friend", 1, PayloadStatus.SUCCESS))
        val retry = async { tracker.send("friend", 2, {}, {}) }
        runCurrent()
        tracker.update("friend", 2, PayloadStatus.SUCCESS)
        retry.await()
    }
}
