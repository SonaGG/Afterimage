package gg.sona.afterimage.index

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

data class IndexKey(val size: Long, val modifiedMillis: Long, val sessionId: UUID) {
    fun write(out: DataOutputStream) {
        out.writeLong(size)
        out.writeLong(modifiedMillis)
        out.writeLong(sessionId.mostSignificantBits)
        out.writeLong(sessionId.leastSignificantBits)
    }

    companion object {
        fun of(recording: Path, sessionId: UUID): IndexKey =
            IndexKey(Files.size(recording), Files.getLastModifiedTime(recording).toMillis(), sessionId)

        fun read(input: DataInputStream): IndexKey =
            IndexKey(input.readLong(), input.readLong(), UUID(input.readLong(), input.readLong()))
    }
}
