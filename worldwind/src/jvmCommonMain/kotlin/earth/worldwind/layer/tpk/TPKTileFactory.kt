package earth.worldwind.layer.tpk
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Sector
import earth.worldwind.layer.mercator.MercatorImageTile
import earth.worldwind.layer.mercator.MercatorSector
import earth.worldwind.render.image.ImageTile
import earth.worldwind.util.Level
import earth.worldwind.util.TileFactory
import java.io.File
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * Tile factory for an ESRI tile package (.tpk) in the Web Mercator tiling scheme, the TPK
 * counterpart of MBTileFactory. The package is opened (and its bundle indexes scanned) in the
 * constructor, so create it off the main thread -- see [TPKLayerFactory].
 *
 * Only the standard 256x256 Web Mercator (ArcGIS Online / Google-compatible) scheme is
 * supported, which is what [TPKFile] assumes when it maps LODs to zoom levels.
 */
open class TPKTileFactory(val contentPath: String) : TileFactory {
    protected val tpkFile = TPKFile(File(contentPath))
    override val contentType = "TPK"


    /** Layer name: the file name without its extension (TPK files carry no title of their own). */
    val contentKey: String = File(contentPath).nameWithoutExtension

    val minZoom: Int
    val maxZoom: Int

    init {
        val zooms = tpkFile.getZoomLevelMap().keys
        if (zooms.isEmpty()) {
            tpkFile.close()
            error("TPK file contains no tiles: $contentPath")
        }
        minZoom = zooms.min().toInt()
        maxZoom = zooms.max().toInt()
    }

    /** Raw conf.xml tile format: JPEG, PNG, PNG8, PNG24, PNG32 or MIXED. */
    val tpkImageFormat: String? = tpkFile.imageFormat

    /** MIME type handed to the decoder; null for MIXED/unknown (decoder sniffs the bytes). */
    protected val imageFormat: String? = when (tpkImageFormat?.uppercase()) {
        "JPEG", "JPG" -> "image/jpeg"
        "PNG", "PNG8", "PNG24", "PNG32" -> "image/png"
        else -> null
    }

    /** JPEG tiles have no alpha, so the layer can use a cheaper pixel format for them. */
    val isOpaque get() = imageFormat == "image/jpeg"

    /** Geographic extent of the data, derived from the tile coverage of the most detailed level. */
    val boundingSector: Sector? = computeBoundingSector()

    @Volatile
    var isShutdown = false
        private set

    fun shutdown() {
        isShutdown = true
        tpkFile.close()
    }

    override fun createTile(sector: Sector, level: Level, row: Int, column: Int): ImageTile =
        buildTile(sector, level, row, column).apply {
            imageSource = buildImageSource(tpkFile, level.levelNumber, column, row, imageFormat)
                .also { it.postprocessor = this }
        }

    protected open fun buildTile(sector: Sector, level: Level, row: Int, column: Int): ImageTile =
        if (sector is MercatorSector) MercatorImageTile(sector, level, row, column)
        else ImageTile(sector, level, row, column)

    private fun computeBoundingSector(): Sector? {
        val z = maxZoom.toLong()
        val minCol = tpkFile.getMinColumn(z)
        val maxCol = tpkFile.getMaxColumn(z)
        val minRow = tpkFile.getMinRow(z)
        val maxRow = tpkFile.getMaxRow(z)
        // min > max means the level's index scan found no tiles at all
        if (minCol < 0 || minRow < 0 || minCol > maxCol || minRow > maxRow) return null

        val n = (1L shl maxZoom).toDouble()
        // Rows count from the south: the bottom edge of minRow / top edge of maxRow.
        val minLat = tileLat(n - minRow, n)
        val maxLat = tileLat(n - 1 - maxRow, n)
        val minLon = minCol / n * 360.0 - 180.0
        val maxLon = (maxCol + 1) / n * 360.0 - 180.0
        return Sector(minLat.degrees, maxLat.degrees, minLon.degrees, maxLon.degrees)
    }

    /** Latitude (degrees) of a Web Mercator tile edge, [yFromTop] tiles below the north edge. */
    private fun tileLat(yFromTop: Double, n: Double) = atan(sinh(PI * (1 - 2 * yFromTop / n))) * 180.0 / PI
}