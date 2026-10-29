package io.github.lujinxin.nextep.framework

/** Freeze fitted-task polling while the panel viewport catches up with native rotation. */
internal class DisplayRotationHandoff {
    data class Ticket(val generation: Long, val rotation: Int, val tasks: Set<Int>, val until: Long)
    private var nextGeneration = 0L
    private var pending: Ticket? = null

    @Synchronized fun begin(rotation: Int, tasks: Set<Int>, now: Long): Ticket =
        Ticket(++nextGeneration, rotation, tasks.toSet(), now + 1_500L).also { pending = it }

    @Synchronized fun ticket(generation: Long): Ticket? = pending?.takeIf { it.generation == generation }

    @Synchronized fun holds(taskId: Int, now: Long): Boolean =
        pending?.let { now < it.until && taskId in it.tasks } == true

    @Synchronized fun complete(generation: Long) {
        if (pending?.generation == generation) pending = null
    }
}
