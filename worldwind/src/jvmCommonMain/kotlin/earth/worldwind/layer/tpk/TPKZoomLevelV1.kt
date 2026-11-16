package earth.worldwind.layer.tpk

import earth.worldwind.layer.tpk.TPKTile.TPKTileSorter
import earth.worldwind.layer.tpk.TPKTile.TileInfo
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min

/**
 * TPKZoomLevel implementation for ESRI Compact Cache V1 files
 *
 * TPK is a ZIP archive
 *
 * In V1 the bundle index is a separate file from the bundle data and index
 * entries are each 5-bytes long. The index is stored in column-major order.
 *
 * @author EagleEye
 */
class TPKZoomLevelV1(
    // injected via constructor
    private var theTPK: ZipFile?,
    private var zipEntryMap: MutableMap<String?, ZipEntry?>?,
    bundleNames: List<String>, // names of bundle data files for this zoom level
    indexNames: List<String>, // names of bundle index files for this zoom level
    override val zoomLevel: Long // zoom level number (0 to a max of 24)
) : TPKZoomLevel() {
    override var minRow: Long = Long.MAX_VALUE
        private set
    override var maxRow: Long = Long.MIN_VALUE
        private set
    override var minColumn: Long = Long.MAX_VALUE
        private set
    override var maxColumn: Long = Long.MIN_VALUE
        private set

    init {
        // maximum value for row and column at this zoom level
        max_row_column = (1L shl zoomLevel.toInt()) - 1
        parseBundles(bundleNames, indexNames.toSet())
    }

    /**
     * On subsequent uses of a TPK file we must use this method to set current references!!
     */
    override fun setTPKandEntryMap(theTPK: ZipFile?, zipEntryMap: MutableMap<String?, ZipEntry?>?) {
        this.theTPK = theTPK
        this.zipEntryMap = zipEntryMap
    }

    /**
     * Read the tile data for the given coverage and return a list of tile objects to the
     * caller. Reads are sorted so we always read forward as much as possible.
     */
    @Synchronized
    override fun getTiles(
        top: Long, bottom: Long, left: Long, right: Long, format: String?
    ): MutableList<TPKTile?> {
        // first find the tile coverage in the bundle indexes
        val tiles = makeTileSet(top, bottom, left, right, format)

        // then sort the tiles into the optimal read order and go read them!!
        tiles.filterNotNull().sortedWith(TPKTileSorter()).forEach { readTileData(it) }
        return tiles
    }

    /**
     * Free held resources and drop references that would prevent garbage collection
     */
    @Synchronized
    override fun releaseResources() {
        bundles.forEach { it.releaseResources() } // close open input streams
        zipEntryMap = null
        theTPK = null
    }

    /**
     * Isolate each bundle data / bundle index "pair" and parse them to create Bundle objects
     */
    private fun parseBundles(bundleNames: List<String>, indexNames: Set<String>) {
        for (bundleName in bundleNames) {
            val m = PARSE_BUNDLE_NAME.matcher(bundleName)
            if (!m.matches()) throw RuntimeException("Unable to parse bundle name: $bundleName")
            // only the extension is swapped (a folder called "bundle" must not be touched)
            val indexName = bundleName.removeSuffix(".bundle") + ".bundlx"
            // do we have a matching bundle index file?
            if (indexName in indexNames) parseBundle(bundleName, indexName, m.group(1)!!, m.group(2)!!)
        }
    }

    /**
     * Do an initial scan of a bundle/index file pair to determine the row and column
     * coverage contained in the bundle and create a bundle object for subsequent access
     */
    private fun parseBundle(bundleName: String, indexName: String, rowStart: String, colStart: String) {
        val baseRow = rowStart.toLong(HEXADECIMAL)
        val baseColumn = colStart.toLong(HEXADECIMAL)

        // as long as row and column are in allowable range ...
        if (baseRow > max_row_column || baseColumn > max_row_column) return

        val bundle = TPKBundle(
            bundleName, indexName, baseColumn, baseRow,
            { theTPK }, { zipEntryMap }
        )

        var indexReadOffset = INDEX_HEADER_LENGTH.toLong() // skip first 16 bytes of index
        cols@ for (col in 0 until BUNDLE_DIMENSION) {
            for (row in 0 until BUNDLE_DIMENSION) {
                // calculate row and column this index entry is for
                var thisRow = baseRow + row
                val thisColumn = baseColumn + col

                val tileDataOffset = getTileDataOffset(bundle, indexReadOffset)

                // make sure the row and column are "in bounds" for this zoom level
                if (thisColumn <= max_row_column && thisRow <= max_row_column) {
                    thisRow = max_row_column - thisRow

                    // if the tile data offset is less than the minimum data offset
                    // then the tile does not exist
                    if (tileDataOffset >= MINIMUM_DATA_OFFSET) {
                        bundle.minRow = min(bundle.minRow, thisRow)
                        bundle.maxRow = max(bundle.maxRow, thisRow)
                        bundle.minColumn = min(bundle.minColumn, thisColumn)
                        bundle.maxColumn = max(bundle.maxColumn, thisColumn)
                    }
                }

                // quit early??
                if (thisColumn == max_row_column && thisRow > max_row_column) break@cols

                indexReadOffset += INDEX_ENTRY_LENGTH // next slot in bundle index
            }
        }

        // update min/max values for the zoom level
        minRow = min(minRow, bundle.minRow)
        maxRow = max(maxRow, bundle.maxRow)
        minColumn = min(minColumn, bundle.minColumn)
        maxColumn = max(maxColumn, bundle.maxColumn)

        bundles.add(bundle)
    }

    /**
     * Read the bundle index for the given set of tiles and return a corresponding list of
     * TPKTile objects. The index is read in the optimal order for this cache format.
     */
    private fun makeTileSet(
        top: Long, bottom: Long, left: Long, right: Long, format: String?
    ): MutableList<TPKTile?> {
        val tiles = ArrayList<TPKTile?>()
        if (bundles.isEmpty()) return tiles

        var bundleIndex = 0
        for (col in left..right) {
            for (row in top downTo bottom) {
                // find bundle for tile; tiles outside every bundle simply don't exist
                val found = findBundleIndex(bundleIndex, col, row)
                if (found < 0) continue
                bundleIndex = found
                val bundle = bundles[bundleIndex]

                // calculate the index position we need to read
                val bundleRow = (max_row_column - row) - bundle.baseRow
                val indexReadOffset = INDEX_HEADER_LENGTH +
                        (((col - bundle.baseColumn) * BUNDLE_DIMENSION) + bundleRow) * INDEX_ENTRY_LENGTH

                // read the tile index and get the offset to the tile data
                val ti = TileInfo(0, getTileDataOffset(bundle, indexReadOffset))
                tiles.add(TPKTile(zoomLevel, col, row, format, ti, bundleIndex))
            }
        }
        return tiles
    }

    /** For a given TPKTile object read the actual tile data */
    private fun readTileData(tile: TPKTile) {
        val bundle = bundles[tile.bundleNum]
        tile.tileData = getTileData(bundle, tile.tileInfo.tileDataOffset)
    }

    /**
     * Given a bundle and a bundle index offset, read the 5-byte data pointer in the index
     * and convert that to a byte-offset in the bundle data file
     */
    fun getTileDataOffset(bundle: TPKBundle, indexReadOffset: Long): Long {
        val tileIndex = bundle.bundleIndx?.read(indexReadOffset, INDEX_ENTRY_LENGTH)
            ?: return 0L // missing/short index: treated as "no tile" (below MINIMUM_DATA_OFFSET)

        // convert 5-byte little-endian value into a byte-offset
        var offset = 0L
        for (i in 0 until INDEX_ENTRY_LENGTH) offset = offset or ((tileIndex[i].toLong() and 0xff) shl (8 * i))
        return offset
    }

    /**
     * Given the offset of the tile data within the bundle, find and retrieve the tile data
     *
     * @return null or a byte-array of tile data
     */
    fun getTileData(bundle: TPKBundle, tileDataOffset: Long): ByteArray? {
        // offsets below the data header can never point at a tile
        if (tileDataOffset < MINIMUM_DATA_OFFSET) return null
        val dataLength = getTileDataLength(bundle, tileDataOffset)
        return if (dataLength > 0) bundle.bundleData.read(tileDataOffset + DATA_LENGTH_LENGTH, dataLength) else null
    }

    /**
     * Read the four bytes at the start of the tile data, which hold the length of the tile data
     */
    private fun getTileDataLength(bundle: TPKBundle, tileDataOffset: Long): Int {
        val dataLen = bundle.bundleData.read(tileDataOffset, DATA_LENGTH_LENGTH) ?: return 0
        var length = 0
        for (i in 0 until DATA_LENGTH_LENGTH) length = length or ((dataLen[i].toInt() and 0xff) shl (8 * i))
        return length
    }

    companion object {
        // pattern isolates the "base" row and column number for each bundle (in hexadecimal)
        val PARSE_BUNDLE_NAME: Pattern = Pattern.compile("^.*/R([0-9a-f]+)C([0-9a-f]+)\\.bundle$")

        const val BUNDLE_DIMENSION: Int = 128 // bundle is 128 columns by 128 rows
        const val INDEX_ENTRY_LENGTH: Int = 5 // each index entry is 5 bytes long
        const val INDEX_HEADER_LENGTH: Int = 16 // first 16 bytes of index file is header data
        const val DATA_HEADER_LENGTH: Int = 60 // first 60 bytes of bundle data file is header
        const val DATA_LENGTH_LENGTH: Int = 4 // tile data length stored in 4 bytes
        const val HEXADECIMAL: Int = 16 // row and column numbers in bundle name are base-16 encoded

        // if tile data offset is less than this value then tile does not exist!
        const val MINIMUM_DATA_OFFSET: Int =
            DATA_HEADER_LENGTH + BUNDLE_DIMENSION * BUNDLE_DIMENSION * DATA_LENGTH_LENGTH // (65596)
    }
}