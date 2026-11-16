package earth.worldwind.layer.tpk

import java.io.IOException
import java.io.InputStream
import java.util.logging.Logger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * A zoom level consists of 1 or more bundles. A bundle is physically always 128
 * columns by 128 rows but may be "logically" smaller (ie it doesn't necessarily
 * have data for each row/column)
 *
 * @author EagleEye
 */
class TPKBundle(
    bundleName: String?,
    indexName: String?,
    /** "first" column represented by bundle */
    var baseColumn: Long,
    /** "first" row represented by bundle */
    var baseRow: Long,
    // when we need to access the TPKFile/specific files within we use these provider functions
    private val tpkFile: () -> ZipFile?,
    private val zipEntryMap: () -> Map<String?, ZipEntry?>?
) {
    /** the bundle data file -- MUST always be provided */
    val bundleData = SequentialInputFile(bundleName)

    /** the bundle index file -- V2 caches don't have bundle index files so this is optional */
    val bundleIndx: SequentialInputFile? = indexName?.let { SequentialInputFile(it) }

    // these four values are populated by the initial scan of the bundle's index and
    // they represent rows and columns for which the bundle has data!
    var minRow: Long = Long.MAX_VALUE
    var maxRow: Long = Long.MIN_VALUE
    var minColumn: Long = Long.MAX_VALUE
    var maxColumn: Long = Long.MIN_VALUE

    fun releaseResources() {
        bundleData.close()
        bundleIndx?.close()
    }

    /**
     * Predicate function to determine if the column,row coordinates are
     * contained in the "coverage" of this bundle
     */
    fun inBundle(column: Long, row: Long): Boolean =
        column in minColumn..maxColumn && row in minRow..maxRow

    /**
     * This object is used to manage the bundle data file and the bundle index
     * file. We are using the InputStream object returned by
     * ZipFile.getInputStream(ZipEntry) to read data from these files. The idea
     * is to read in a forward direction (skipping bytes appropriately) whenever
     * possible. If the request is for an offset that is "behind" our current
     * position the object will close the current InputStream, get a "new" one
     * and satisfy the read request.
     *
     * The stream is shared state, so reads are synchronized (WorldWind requests tiles
     * from several threads).
     */
    inner class SequentialInputFile(
        // path/name from the zipEntryMap
        private val name: String?
    ) {
        private var stream: InputStream? = null
        private var currentReadPosition: Long = 0

        /**
         * At a given offset read a given number of bytes and return them as a new byte array
         *
         * @return a byte array of "length" bytes, or null if the file ends before that
         */
        @Synchronized
        fun read(offset: Long, length: Int): ByteArray? {
            val result = ByteArray(length)
            try {
                // if the stream isn't open or if the stream is "beyond" the offset, (re)open it
                if (stream == null || currentReadPosition > offset) {
                    close()
                    val zipEntry = zipEntryMap()?.get(name)
                        ?: throw IOException("Zip entry not found: $name")
                    val zip = tpkFile() ?: throw IOException("TPK file is closed")
                    stream = zip.getInputStream(zipEntry)
                }
                val input = stream!!

                // skip forward to the requested offset
                var toSkip = offset - currentReadPosition
                while (toSkip > 0) {
                    val skipped = input.skip(toSkip)
                    if (skipped > 0) {
                        toSkip -= skipped
                        currentReadPosition += skipped
                    } else {
                        // skip() may legally return 0: fall back to consuming one byte
                        if (input.read() == -1) return null
                        toSkip--
                        currentReadPosition++
                    }
                }

                // the stream may return fewer bytes than asked for, so loop
                var readOffset = 0
                while (readOffset < length) {
                    val bytesRead = input.read(result, readOffset, length - readOffset)
                    if (bytesRead == -1) {
                        Logger.getLogger(TPKBundle::class.java.name).warning("Didn't read correctly: $name")
                        return null
                    }
                    readOffset += bytesRead
                    currentReadPosition += bytesRead
                }
                return result
            } catch (ex: IOException) {
                close() // stream position is unknown now
                throw RuntimeException("Caught error reading sequential input ZipEntry stream", ex)
            }
        }

        /** Release resources and re-initialize members */
        @Synchronized
        fun close() {
            try {
                stream?.close()
            } catch (ex: IOException) {
                // don't care
            }
            stream = null
            currentReadPosition = 0
        }
    }
}