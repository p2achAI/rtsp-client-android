package ir.am3n.rtsp.client.data

import com.google.android.renderscript.YuvFormat

data class YuvFrame(
    var data: ByteArray,
    var format: YuvFormat?
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as YuvFrame

        if (!data.contentEquals(other.data)) return false
        if (format != other.format) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + format.hashCode()
        return result
    }

    override fun toString(): String {
        // Avoid dumping the whole byte array (Arrays.toString) which triggers massive allocations.
        // Keep it lightweight to prevent jank/GC pressure when logs print YuvFrame.
        val size = data.size
        val fmtName = format?.toString() ?: "null"
        return "YuvFrame(size=" + size + ", format=" + fmtName + ")"
    }
}
