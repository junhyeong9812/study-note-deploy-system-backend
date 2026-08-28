package xyz.junproject.backend.search.domain

data class SearchHit(val path: String, val chunkNo: Int, val heading: String,
                     val snippet: String, val docKind: String, val score: Double)
