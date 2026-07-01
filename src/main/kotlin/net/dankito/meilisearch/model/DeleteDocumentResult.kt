package net.dankito.meilisearch.model

data class DeleteDocumentResult(
    val type: DeleteDocumentResultType,
    val result: TaskResult,
    val error: String? = null,
)
