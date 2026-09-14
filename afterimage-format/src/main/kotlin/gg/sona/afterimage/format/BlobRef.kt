package gg.sona.afterimage.format


data class BlobRef(val hash: Long, val segmentIndex: Int, val offset: Int, val length: Int)
