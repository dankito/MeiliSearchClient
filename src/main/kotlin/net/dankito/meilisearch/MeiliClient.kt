package net.dankito.meilisearch

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.meilisearch.sdk.Client
import com.meilisearch.sdk.Config
import com.meilisearch.sdk.model.DocumentQuery
import com.meilisearch.sdk.model.DocumentsQuery
import com.meilisearch.sdk.model.Settings
import com.meilisearch.sdk.model.Task
import com.meilisearch.sdk.model.TaskInfo
import com.meilisearch.sdk.model.TaskStatus
import kotlinx.coroutines.delay
import net.dankito.meilisearch.model.SearchResults
import net.dankito.meilisearch.model.TaskFailure
import net.dankito.meilisearch.model.TaskResult
import net.dankito.meilisearch.model.TaskSuccess
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.milliseconds

open class MeiliClient(
    meiliHost: String,
    meiliApiKey: String? = null,
    protected val objectMapper: ObjectMapper = ObjectMapper().apply {
        registerKotlinModule()
        findAndRegisterModules()

        disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    }
) {

    companion object {
        const val DefaultTaskTimeoutMs = 60_000
        const val DefaultTaskCheckIntervalMs = 25
    }


    val client = Client(Config(meiliHost, meiliApiKey))


    suspend fun configureIndex(indexName: String, primaryKey: String? = null,
                               searchableAttributes: Collection<String>? = null, // Welche Felder gehen in die Volltext-Suche (Ranking nach Reihenfolge)
                               filterableAttributes: Collection<String>? = null, // Welche Felder dürfen gefiltert/gefacettiert werden
                               sortableAttributes: Collection<String>? = null, // Welche Felder können sortiert werden
                               displayedAttributes: List<String>? = null,   // which attributes are returned from index in case not specified otherwise, null = all
                               rankingRules: List<String>? = null,          // null = Meili defaults
    ): TaskResult {
        val index = client.index(indexName)

        if (primaryKey != null && index.primaryKey == null) {
            // Error message if index already exists:
            // Meilisearch task 27801 failed: invalid_request index_already_exists (https://docs.meilisearch.com/errors#index_already_exists): Index `WebPageParser_DiscoveredPages` already exists.
            val createResult = waitForTask(client.createIndex(indexName, primaryKey))
        }

        val settings = Settings()

        // Welche Felder gehen in die Volltext-Suche (Ranking nach Reihenfolge)
        searchableAttributes?.let { settings.searchableAttributes = it.toTypedArray() }
        // Welche Felder dürfen gefiltert/gefacettiert werden
        filterableAttributes?.let { settings.filterableAttributes = it.toTypedArray() }
        // Welche Felder können sortiert werden
        sortableAttributes?.let { settings.sortableAttributes = it.toTypedArray() }
        displayedAttributes?.let { settings.displayedAttributes = it.toTypedArray() }
        rankingRules?.let { settings.rankingRules = it.toTypedArray() }


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


        val settingsTask = index.updateSettings(settings)
        return waitForTask(settingsTask.taskUid)
    }



    inline fun <reified T> getDocument(indexName: String, id: String, fieldsToReturn: Collection<String>? = null): T? =
        try {
            val index = client.index(indexName)

            if (fieldsToReturn.isNullOrEmpty()) {
                index.getDocument(id, T::class.java)
            } else {
                val query = DocumentQuery().apply { setFields(fieldsToReturn.toTypedArray()) }
                index.getDocument(id, query, T::class.java)
            }
        } catch (e: Exception) {
            if (isNotFoundError(e)) {
                null
            } else {
                throw e
            }
        }

    open fun getDocumentJson(indexName: String, id: String, fieldsToReturn: Collection<String>? = null): String? =
        getDocument(indexName, id, fieldsToReturn)


    inline fun <reified T : Any> getAllDocuments(indexName: String, fieldsToReturn: Collection<String> = emptyList(),
                                  filter: Collection<String> = emptyList()): List<T> =
        getAllDocuments(indexName, T::class, fieldsToReturn, filter)

    open fun <T : Any> getAllDocuments(indexName: String, resultClass: KClass<T>, fieldsToReturn: Collection<String> = emptyList(),
                                  filter: Collection<String> = emptyList()): List<T> {
        var page = 0
        val batchSize = 1000
        val result = mutableListOf<T>()

        var batch = getDocuments(indexName, resultClass, page * batchSize, batchSize, fieldsToReturn, filter)
        result.addAll(batch.results)

        while (batch.results.size == batchSize) {
            page += 1
            batch = getDocuments(indexName, resultClass, page * batchSize, batchSize, fieldsToReturn, filter)
            result.addAll(batch.results)
        }

        return result
    }

    fun <T : Any> getDocuments(indexName: String, resultClass: KClass<T>, offset: Int = 0, limit: Int = 20,
                               fieldsToReturn: Collection<String> = emptyList(), filter: Collection<String> = emptyList()): SearchResults<T> {
        val query = DocumentsQuery().setOffset(offset).setLimit(limit).apply {
            if (fieldsToReturn.isNotEmpty()) {
                setFields(fieldsToReturn.toTypedArray())
            }
            if (filter.isNotEmpty()) {
                setFilter(filter.toTypedArray())
            }
        }

        // TODO: cache index and type
        val rawDocuments = client.index(indexName).getRawDocuments(query)

        val type = objectMapper.typeFactory.constructParametricType(SearchResults::class.java, resultClass.java)

        return objectMapper.readValue(rawDocuments, type) as SearchResults<T>
    }


    open suspend fun index(indexName: String, document: Any): TaskResult =
        index(indexName, listOf(document))

    open suspend fun index(indexName: String, documents: List<Any>): TaskResult {
        val documentJson = objectMapper.writeValueAsString(documents)

        val taskInfo = indexDocumentsJson(indexName, documentJson)

        return waitForTask(taskInfo.taskUid)
    }

    protected open suspend fun indexDocumentsJson(indexName: String, documentsJson: String): TaskResult =
        waitForTask(client.index(indexName).addDocuments(documentsJson))


    suspend fun updateDocuments(indexName: String, documents: Collection<Any>, primaryKey: String? = null, batchSize: Int? = null): TaskResult {
        if (documents.isEmpty()) {
            return TaskFailure(-1, null, "No documents to update")
        }

        val index = client.index(indexName)
        val documentsJson = objectMapper.writeValueAsString(documents)

        return if (batchSize != null) {
            val taskInfos = index.updateDocumentsInBatches(documentsJson, batchSize, primaryKey)
            val results = taskInfos.map { waitForTask(it) }
            results.firstOrNull { it is TaskFailure } ?: results.last()
        } else {
            waitForTask(index.updateDocuments(documentsJson, primaryKey))
        }
    }


    suspend fun deleteDocument(indexName: String, documentId: String): TaskResult {
        val taskInfo = client.index(indexName).deleteDocument(documentId)
        return waitForTask(taskInfo)
    }


    suspend fun compactAllIndices() {
        client.indexes.results.forEach { index ->
            waitForTask(index.compact())
        }
    }

    suspend fun compactIndex(indexName: String) {
        waitForTask(client.getIndex(indexName).compact())
    }


    suspend fun waitForTask(taskInfo: TaskInfo, timeoutMs: Int = DefaultTaskTimeoutMs, intervalMs: Int = DefaultTaskCheckIntervalMs) =
        waitForTask(taskInfo.taskUid, timeoutMs, intervalMs)

    suspend fun waitForTask(taskUid: Int, timeoutMs: Int = DefaultTaskTimeoutMs, intervalMs: Int = DefaultTaskCheckIntervalMs): TaskResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        val interval = intervalMs.milliseconds

        while (System.currentTimeMillis() < deadline) {
            val task = client.getTask(taskUid)
            when (task.status) {
                TaskStatus.SUCCEEDED -> return TaskSuccess(taskUid, task)
                TaskStatus.FAILED    -> return TaskFailure(taskUid, task, "Meilisearch task $taskUid failed: ${formatError(task)}")
                TaskStatus.CANCELED  -> return TaskFailure(taskUid, task, "Meilisearch task $taskUid was canceled")
                else                 -> delay(interval)
            }
        }

        return TaskFailure(taskUid, null, "Meilisearch task $taskUid timed out after ${timeoutMs}ms")
    }

    suspend fun waitForTaskSuccessOrThrow(task: TaskInfo, timeoutMs: Int = DefaultTaskTimeoutMs, intervalMs: Int = DefaultTaskCheckIntervalMs) =
        waitForTaskSuccessOrThrow(task.taskUid, timeoutMs, intervalMs)

    suspend fun waitForTaskSuccessOrThrow(taskUid: Int, timeoutMs: Int = DefaultTaskTimeoutMs, intervalMs: Int = DefaultTaskCheckIntervalMs): Task {
        val taskResult = waitForTask(taskUid, timeoutMs, intervalMs)

        when (taskResult) {
            is TaskSuccess -> return taskResult.task!!
            is TaskFailure ->  error("Meilisearch task $taskUid failed: ${taskResult.error}")
        }

    }


    open fun formatError(task: Task): String = task.error?.let { error ->
        "${error.type} ${error.code} ${error.message}${error.link.takeUnless { it.isNullOrBlank() }?.let { " (${it})" } ?: ""}"
    } ?: "<error not set>"

    open fun isNotFoundError(e: Throwable): Boolean =
        e.message?.contains("document_not_found") == true ||
                e.message?.contains("404") == true

}