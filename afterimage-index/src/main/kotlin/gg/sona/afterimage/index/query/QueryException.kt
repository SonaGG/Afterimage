package gg.sona.afterimage.index.query

class QueryException(message: String, val position: Int = -1) : RuntimeException(message)
