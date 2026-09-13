package gg.sona.recast.index.query

class SearchResult(
    val query: String,
    val hits: List<SearchHit>,
    val error: String?,
    val errorPosition: Int,
    val elapsedMillis: Long,
    val truncated: Boolean,
) {
    val ok: Boolean get() = error == null

    companion object {
        fun failure(query: String, message: String, position: Int): SearchResult =
            SearchResult(query, emptyList(), message, position, 0L, false)
    }
}
