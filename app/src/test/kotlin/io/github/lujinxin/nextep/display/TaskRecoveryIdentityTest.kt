package io.github.lujinxin.nextep.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskRecoveryIdentityTest {
    private data class Task(val id: Int, val user: Int, val pkg: String)
    private val primary = Task(18, 0, "qq")
    private val resolver = Task(17, 999, "android")

    @Test fun anOpenResolverDoesNotMeanTheCloneProcessDied() {
        assertEquals(resolver, find(listOf(primary, resolver)))
    }

    @Test fun primaryTaskCannotSatisfyCloneRecovery() {
        assertNull(find(listOf(primary)))
    }

    @Test fun aRecreatedTaskMustBelongToTheSameUser() {
        val recreated = Task(19, 999, "qq")
        assertEquals(recreated, find(listOf(primary, recreated)))
    }

    @Test fun anUnknownUserDoesNotDefaultToPrimary() {
        assertNull(find(listOf(primary), user = null))
    }

    private fun find(tasks: List<Task>, user: Int? = 999) =
        TaskRecoveryIdentity(17, user, "qq").find(tasks, Task::id, Task::user, Task::pkg)
}
