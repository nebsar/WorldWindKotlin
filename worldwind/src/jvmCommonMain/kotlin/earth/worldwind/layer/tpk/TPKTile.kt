package earth.worldwind.layer.tpk

/**
 * Tile object returned to the reader
 */
class TPKTile(
    var zoomLevel: Long,
    /** column -- longitude */
    var col: Long,
    /** row -- latitude */
    var row: Long,
    /** JPEG or PNG */
    var imageFormat: String?,
    /** data length and offset for read */
    var tileInfo: TileInfo,
    /** index into the zoom level bundles list */
    var bundleNum: Int
) {
    /** unconverted tile data; null if the tile does not exist or could not be read */
    var tileData: ByteArray? = null

    /**
     * Sort comparator -- sorts tiles by bundle and then the tile data offset, so reads
     * move forward through each bundle file
     */
    class TPKTileSorter : Comparator<TPKTile> {
        override fun compare(a: TPKTile, b: TPKTile): Int {
            val byBundle = a.bundleNum.compareTo(b.bundleNum)
            return if (byBundle != 0) byBundle
            else a.tileInfo.tileDataOffset.compareTo(b.tileInfo.tileDataOffset)
        }
    }

    /**
     * Temporary utility object to keep tile location/length info
     *
     * Note that in a V1 cache only the tileDataOffset field is utilized as the
     * length is stored at that location (32-bit int) and the actual tile data
     * is at tileDataOffset+4
     *
     * In a V2 cache both fields are used and populated directly from the tile's
     * index entry
     */
    class TileInfo(var tileLength: Int, var tileDataOffset: Long)
}