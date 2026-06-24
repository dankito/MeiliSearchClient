package net.dankito.meilisearch.model

import com.meilisearch.sdk.model.Task

sealed class TaskResult(
    val taskUid: Int,
    val task: Task?,
    val isSuccess: Boolean
)

class TaskSuccess(taskUid: Int, task: Task) : TaskResult(taskUid, task, true) {
    override fun toString() = "Task $taskUid succeeded"
}

class TaskFailure(taskUid: Int, task: Task?, val error: String) : TaskResult(taskUid, task, false) {
    override fun toString() = "Task $taskUid failed: $error"
}
