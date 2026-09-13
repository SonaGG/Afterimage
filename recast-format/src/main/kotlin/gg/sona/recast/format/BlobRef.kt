package gg.sona.recast.format


data class BlobRef(val hash: Long, val segmentIndex: Int, val offset: Int, val length: Int)
