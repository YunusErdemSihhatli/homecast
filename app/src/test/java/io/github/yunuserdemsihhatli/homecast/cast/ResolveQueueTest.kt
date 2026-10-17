package io.github.yunuserdemsihhatli.homecast.cast

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolveQueueTest {
    private class FakeScheduler : Scheduler {
        class Task(
            val at: Long,
            val run: () -> Unit,
            var cancelled: Boolean = false,
        )

        var now = 0L
        val tasks = mutableListOf<Task>()

        override fun postDelayed(
            delayMs: Long,
            task: () -> Unit,
        ): () -> Unit {
            val t = Task(now + delayMs, task)
            tasks += t
            return { t.cancelled = true }
        }

        fun advance(ms: Long) {
            val target = now + ms
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: break
                tasks -= next
                now = next.at
                next.run()
            }
            now = target
        }
    }

    private val scheduler = FakeScheduler()
    private val started = mutableListOf<String>()
    private val dones = mutableMapOf<String, (Boolean) -> Unit>()
    private val queue =
        ResolveQueue<String>(scheduler, { item, done ->
            started += item
            dones[item] = done
        })

    @Test
    fun resolvesOneAtATime() {
        queue.enqueue("a")
        queue.enqueue("b")
        assertEquals(listOf("a"), started)
        dones.getValue("a")(true)
        assertEquals(listOf("a", "b"), started)
    }

    @Test
    fun failedResolveIsRetriedTwiceThenDropped() {
        queue.enqueue("a")
        repeat(3) {
            dones.getValue("a")(false)
            scheduler.advance(500)
        }
        assertEquals(listOf("a", "a", "a"), started)
    }

    @Test
    fun watchdogAdvancesPastSilentResolve() {
        queue.enqueue("a")
        queue.enqueue("b")
        scheduler.advance(8_000)
        assertEquals(listOf("a", "b"), started)
    }

    @Test
    fun staleCallbackAfterWatchdogIsIgnored() {
        queue.enqueue("a")
        queue.enqueue("b")
        val lateA = dones.getValue("a")
        scheduler.advance(8_000)
        lateA(true) // must not release b's slot
        queue.enqueue("c")
        assertEquals(listOf("a", "b"), started)
    }

    @Test
    fun removedItemIsNotRetried() {
        queue.enqueue("a")
        queue.remove { it == "a" }
        dones.getValue("a")(false)
        scheduler.advance(10_000)
        assertEquals(listOf("a"), started)
    }

    @Test
    fun throwingResolverAdvances() {
        val q =
            ResolveQueue<String>(scheduler, { item, done ->
                if (item ==
                    "x"
                ) {
                    error("boom")
                } else {
                    started += item.also { done(true) }
                }
            })
        q.enqueue("x")
        q.enqueue("y")
        assertEquals(listOf("y"), started)
    }
}
