package net.dankito.meilisearch.model

data class SearchResults<T>(
    val results: List<T>,
    val offset: Int,
    val limit: Int,
    val total: Int,
)