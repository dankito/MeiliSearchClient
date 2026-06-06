package net.dankito.meilisearch

import com.meilisearch.sdk.Client
import com.meilisearch.sdk.Config
import com.meilisearch.sdk.model.Task
import com.meilisearch.sdk.model.TaskInfo
import com.meilisearch.sdk.model.TaskStatus
import kotlinx.coroutines.delay
import net.dankito.meilisearch.model.TaskFailure
import net.dankito.meilisearch.model.TaskResult
import net.dankito.meilisearch.model.TaskSuccess
import kotlin.time.Duration.Companion.milliseconds

open class MeiliClient(
    meiliHost: String,
    meiliApiKey: String? = null,
) {

    internal val client = Client(Config(meiliHost, meiliApiKey))


    suspend fun configureIndex(indexName: String, searchableAttributes: Collection<String> = emptyList(), // Welche Felder gehen in die Volltext-Suche (Ranking nach Reihenfolge)
                               filterableAttributes: Collection<String> = emptyList(), // Welche Felder dürfen gefiltert/gefacettiert werden
                               sortableAttributes: Collection<String> = emptyList(), // Welche Felder können sortiert werden
    ) {
        val index = client.index(indexName)

        // Welche Felder dürfen gefiltert/gefacettiert werden
        if (filterableAttributes.isNotEmpty()) {
            val taskInfo = index.updateFilterableAttributesSettings(filterableAttributes.toTypedArray())
            val task = waitForTaskSuccessOrThrow(taskInfo)
        }

        // Welche Felder können sortiert werden
        if (sortableAttributes.isNotEmpty()) {
            val taskInfo = index.updateSortableAttributesSettings(sortableAttributes.toTypedArray())
            waitForTaskSuccessOrThrow(taskInfo)
        }

        // Welche Felder gehen in die Volltext-Suche (Ranking nach Reihenfolge)
        if (searchableAttributes.isNotEmpty()) {
            val taskInfo = index.updateSearchableAttributesSettings(searchableAttributes.toTypedArray())
            waitForTaskSuccessOrThrow(taskInfo)
        }


//        val embedderSettings = mapOf(
//            "default" to mapOf(
//                "source" to "openAi",
//                "apiKey" to openAiApiKey,
//                "model" to "text-embedding-3-small",
//                "documentTemplate" to "{{doc.title}} {{doc.overviewMarkdown}} {{doc.tripDescriptionMarkdown}}",
//            )
//        )
//        // Per REST da das Java-SDK Embedder noch nicht vollständig unterstützt:
//        index.rawSearch("""{"embedders": ${objectMapper.writeValueAsString(embedderSettings)}}""")
    }


    suspend fun waitForTask(taskInfo: TaskInfo, timeoutMs: Long = 60_000, intervalMs: Long = 200) =
        waitForTask(taskInfo.taskUid, timeoutMs, intervalMs)

    suspend fun waitForTask(taskUid: Int, timeoutMs: Long = 60_000, intervalMs: Long = 200): TaskResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        val interval = intervalMs.milliseconds

        while (System.currentTimeMillis() < deadline) {
            val task = client.getTask(taskUid)
            when (task.status) {
                TaskStatus.SUCCEEDED -> return TaskSuccess(taskUid, task)
                TaskStatus.FAILED    -> return TaskFailure(taskUid, task, "Meilisearch task $taskUid failed: ${task.error?.type} ${task.error?.code} (${task.error?.link}): ${task.error?.message}")
                TaskStatus.CANCELED  -> return TaskFailure(taskUid, task, "Meilisearch task $taskUid was canceled")
                else                 -> delay(interval)
            }
        }

        return TaskFailure(taskUid, null, "Meilisearch task $taskUid timed out after ${timeoutMs}ms")
    }

    suspend fun waitForTaskSuccessOrThrow(task: TaskInfo, timeoutMs: Long = 60_000, intervalMs: Long = 200) =
        waitForTaskSuccessOrThrow(task.taskUid, timeoutMs, intervalMs)

    suspend fun waitForTaskSuccessOrThrow(taskUid: Int, timeoutMs: Long = 60_000, intervalMs: Long = 200): Task {
        val taskResult = waitForTask(taskUid, timeoutMs, intervalMs)

        when (taskResult) {
            is TaskSuccess -> return taskResult.task!!
            is TaskFailure ->  error("Meilisearch task $taskUid failed: ${taskResult.error}")
        }

    }

}