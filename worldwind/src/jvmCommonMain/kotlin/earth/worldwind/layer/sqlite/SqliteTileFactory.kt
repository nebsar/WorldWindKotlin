package earth.worldwind.layer.sqlite

import earth.worldwind.geom.Sector
import earth.worldwind.layer.cache.CachePolicy
import earth.worldwind.layer.cache.ContentEntry
import earth.worldwind.layer.mercator.MercatorImageTile
import earth.worldwind.layer.mercator.MercatorSector
import earth.worldwind.render.image.ImageSource
import earth.worldwind.render.image.ImageTile
import earth.worldwind.util.Level
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.TileFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Instant

/**
 * Platform database handle for RMaps/OsmAnd-style ".sqlitedb" tile files:
 *   tiles(x INTEGER, y INTEGER, z INTEGER, s INTEGER, image BLOB)
 *   info(minzoom, maxzoom, ...)
 */
expect class SqliteTileStore(contentPath: String, readOnly: Boolean) {
    val isOpen: Boolean
    fun hasTileTable(): Boolean
    fun infoValue(key: String): String?
    /** [zoom] is the standard zoom and [row] the WorldWind (south-origin) row; the store maps both to the file's scheme. */
    fun readTile(zoom: Int, column: Int, row: Int, zoomInverted: Boolean): ByteArray?
    fun writeTile(zoom: Int, column: Int, row: Int, zoomInverted: Boolean, data: ByteArray)
    fun clearTiles()
    fun close()
}

expect fun buildSqliteImageSource(
    store: SqliteTileStore, readOnly: Boolean, contentKey: String,
    zoom: Int, column: Int, row: Int, imageFormat: String, zoomInverted: Boolean
): ImageSource

open class SqliteTileFactory(
    override val contentPath: String,
    val isReadOnly: Boolean,
    /** RMaps/OsmAnd files store z as (17 - zoom). */
    val isZoomInverted: Boolean = true,
    protected val imageFormat: String = "image/png"
) : TileFactory, ContentEntry {
    protected val store = SqliteTileStore(contentPath, isReadOnly)
    protected val contentFile = File(contentPath)
    override val contentType = if (store.hasTileTable()) "SQLite" else {
        store.close()
        error("Not a supported SQLite tile file (needs tiles(x, y, z, image) or tile_index(zoom, x, y, data)). MBTiles files must use the .mbtiles layer.")
    }
    override val contentKey: String = contentFile.nameWithoutExtension

    // info.minzoom / info.maxzoom are stored in the file's own zoom scheme; normalize to standard zoom
    private val rawMin = store.infoValue("minzoom")?.toIntOrNull()
    private val rawMax = store.infoValue("maxzoom")?.toIntOrNull()
    val minZoom = (if (isZoomInverted) rawMax?.let { 17 - it } else rawMin) ?: 0
    val maxZoom = (if (isZoomInverted) rawMin?.let { 17 - it } else rawMax) ?: 20
    val isShutdown get() = !store.isOpen

    fun shutdown() = store.close()

    override suspend fun lastModifiedDate(): Instant = withContext(Dispatchers.IO) {
        Instant.fromEpochMilliseconds(contentFile.lastModified())
    }

    override suspend fun contentSize(): Long = withContext(Dispatchers.IO) { contentFile.length() } // One file, one map

    var cachePolicy: CachePolicy = CachePolicy.UNBOUNDED
        set(value) {
            if (!value.isUnbounded && field.isUnbounded) {
                logMessage(WARN, "SqliteTileFactory", "cachePolicy",
                    "SQLite tile files have no per-tile timestamps; eviction policy ignored for $contentPath")
            }
            field = value
        }

    suspend fun evict() {}

    override suspend fun clearEntry(deleteMetadata: Boolean) {
        withContext(Dispatchers.IO) {
            if (isReadOnly) error("Database is readonly!")
            if (deleteMetadata) {
                store.close()
                contentFile.delete()
            } else store.clearTiles()
        }
    }

    override fun createTile(sector: Sector, level: Level, row: Int, column: Int) =
        buildTile(sector, level, row, column).apply {
            imageSource = buildSqliteImageSource(
                store, isReadOnly, contentKey, level.levelNumber, column, row, imageFormat, isZoomInverted
            ).also { it.postprocessor = this }
        }

    protected open fun buildTile(sector: Sector, level: Level, row: Int, column: Int) = if (sector is MercatorSector) {
        MercatorImageTile(sector, level, row, column)
    } else {
        ImageTile(sector, level, row, column)
    }
}