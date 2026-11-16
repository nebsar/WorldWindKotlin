package earth.worldwind.layer.tpk

import earth.worldwind.layer.tpk.TPKTile.TPKTileSorter
import earth.worldwind.layer.tpk.TPKTile.TileInfo
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min

/**
 * TPKZoomLevel implementation for ESRI Compact Cache V2 files
 *
 * TPK is a ZIP archive
 *
 * In V2 the bundle index is incorporated into the same file as the bundle
 * data. The index entries are each 8-bytes long and have both the tile data
 * length and tile data offset.
 *
 * Also note that the index is stored in row-major order INSTEAD of column-major
 * order!
 *
 * @author EagleEye
 */
class TPKZoomLevelV2(
    // injected via constructor or via "setTPKandEntryMap"
    private var theTPK: ZipFile?,
    private var zipEntryMap: MutableMap<String?, ZipEntry?>?,
    bundleNames: List<String>, // path/names for all bundles in this zoom level
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
        parseBundles(bundleNames)
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

    private fun parseBundles(bundleNames: List<String>) {
        for (bundleName in bundleNames) {
            val m = PARSE_BUNDLE_NAME.matcher(bundleName)
            if (!m.matches()) throw RuntimeException("Unable to parse bundle name: $bundleName")
            parseBundle(bundleName, m.group(1)!!, m.group(2)!!)
        }
    }

    /**
     * Do an initial scan of a bundle index to determine the row and column coverage
     * contained in the bundle and create a bundle object for subsequent access
     */
    private fun parseBundle(bundleName: String, rowStart: String, colStart: String) {
        val baseRow = rowStart.toLong(HEXADECIMAL)
        val baseColumn = colStart.toLong(HEXADECIMAL)

        // as long as row and column are in allowable range ...
        if (baseRow > max_row_column || baseColumn > max_row_column) return

        val bundle = TPKBundle(
            bundleName, null, baseColumn, baseRow,
            { theTPK }, { zipEntryMap }
        )

        var indexReadOffset = DATA_HEADER_LENGTH.toLong() // skip first 64 bytes
        rows@ for (row in 0 until BUNDLE_DIMENSION) {
            for (col in 0 until BUNDLE_DIMENSION) {
                // calculate row and column this index entry is for
                var thisRow = baseRow + row
                val thisColumn = baseColumn + col

                val tileInfo = getTileInfo(bundle, indexReadOffset)

                // make sure the row and column are "in bounds" for this zoom level
                if (thisColumn <= max_row_column && thisRow <= max_row_column) {
                    thisRow = max_row_column - thisRow

                    // if the tile length is zero then the tile does not exist
                    if (tileInfo.tileLength > 0) {
                        bundle.minRow = min(bundle.minRow, thisRow)
                        bundle.maxRow = max(bundle.maxRow, thisRow)
                        bundle.minColumn = min(bundle.minColumn, thisColumn)
                        bundle.maxColumn = max(bundle.maxColumn, thisColumn)
                    }
                }

                // quit early??
                if (thisRow == max_row_column && thisColumn > max_row_column) break@rows

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
        for (row in top downTo bottom) {
            for (col in left..right) {
                // find bundle for tile; tiles outside every bundle simply don't exist
                val found = findBundleIndex(bundleIndex, col, row)
                if (found < 0) continue
                bundleIndex = found
                val bundle = bundles[bundleIndex]

                // calculate position of the tile index
                val bundleRow = (max_row_column - row) - bundle.baseRow
                val indexReadOffset = DATA_HEADER_LENGTH +
                        ((bundleRow * BUNDLE_DIMENSION) + (col - bundle.baseColumn)) * INDEX_ENTRY_LENGTH

                // read the tile index and get the offset/length of the tile data
                val ti = getTileInfo(bundle, indexReadOffset)
                tiles.add(TPKTile(zoomLevel, col, row, format, ti, bundleIndex))
            }
        }
        return tiles
    }

    /** For a given TPKTile object read the actual tile data */
    private fun readTileData(tile: TPKTile) {
        val bundle = bundles[tile.bundleNum]
        val ti = tile.tileInfo
        tile.tileData =
            if (ti.tileLength > 0) bundle.bundleData.read(ti.tileDataOffset, ti.tileLength) else null
    }

    /**
     * Given a bundle and a bundle index offset, read the 8-byte index entry: a 5-byte
     * little-endian data offset followed by a 3-byte little-endian data length.
     */
    fun getTileInfo(bundle: TPKBundle, indexReadOffset: Long): TileInfo {
        val tileIndex = bundle.bundleData.read(indexReadOffset, INDEX_ENTRY_LENGTH)
            ?: return TileInfo(0, 0) // index entry past the end of the file: no tile

        var dataOffset = 0L
        for (i in 0 until 5) dataOffset = dataOffset or ((tileIndex[i].toLong() and 0xff) shl (8 * i))

        var dataLength = 0
        for (i in 0 until 3) dataLength = dataLength or ((tileIndex[5 + i].toInt() and 0xff) shl (8 * i))

        return TileInfo(dataLength, dataOffset)
    }

    companion object {
        // pattern isolates the "base" row and column number for each bundle (in hexadecimal)
        val PARSE_BUNDLE_NAME: Pattern = Pattern.compile("^.*/R([0-9a-f]+)C([0-9a-f]+)\\.bundle$")

        const val BUNDLE_DIMENSION: Int = 128 // bundle is 128 columns by 128 rows
        const val INDEX_ENTRY_LENGTH: Int = 8 // each index entry is 8 bytes long in a V2 cache
        const val DATA_HEADER_LENGTH: Int = 64 // first 64 bytes of V2 bundle data file is header
        const val HEXADECIMAL: Int = 16 // row and column numbers in bundle name are base-16 encoded
    }
}