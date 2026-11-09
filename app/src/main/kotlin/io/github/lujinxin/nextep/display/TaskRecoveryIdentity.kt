package io.github.lujinxin.nextep.display

/** Recovery must survive a resolver becoming topmost without crossing into another user. */
internal data class TaskRecoveryIdentity(val taskId: Int, val userId: Int?, val packageName: String) {
    fun <T> find(tasks: List<T>, id: (T) -> Int, user: (T) -> Int?, pkg: (T) -> String): T? {
        if (userId == null || userId < 0) return null
        val sameUser = tasks.filter { user(it) == userId }
        return sameUser.firstOrNull { id(it) == taskId }
            ?: sameUser.firstOrNull { pkg(it) == packageName }
    }
}
