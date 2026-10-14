package com.thatsmyface.nearby

import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class TaskAwaitInstrumentedTest {
    @Test fun sdkCancellationIsRecoverableAndTheNextOperationStillRuns() = runBlocking {
        withTimeout(5_000) {
            var failed = false
            try { Tasks.forCanceled<String>().awaitResult() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IOException) { failed = true }
            assertTrue(failed)
            assertTrue(isActive)
            assertEquals("next operation", Tasks.forResult("next operation").awaitResult())
        }
    }

    @Test fun cancellingTheSharingCoroutineIgnoresLateSdkCompletion() = runBlocking {
        withTimeout(5_000) {
            val task = TaskCompletionSource<String>()
            var continued = false
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                task.task.awaitResult()
                continued = true
            }
            waiting.cancelAndJoin()
            task.setResult("late result")
            withContext(Dispatchers.Main) { }
            assertTrue(waiting.isCancelled)
            assertFalse(continued)
            assertTrue(isActive)
        }
    }
}
