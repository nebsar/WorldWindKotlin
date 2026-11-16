package earth.worldwind.layer.tpk

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToLong

/**
 * Reader for ESRI tile package (.tpk) files: a ZIP archive holding a conf.xml and
 * compact-cache (V1/V2) bundles.
 *
 * Opening the file parses conf.xml and builds a [TPKZoomLevel] for each zoom level found.
 *
 * @author EagleEye
 */
class TPKFile(theFile: File) {
    // the TPK file (a TPK file is a ZIP file)
    private var theTPK: ZipFile? = null

    // map of contents of TPK file by file "path/name"
    private val zipEntryMap: MutableMap<String?, ZipEntry?> = HashMap()

    // TPKZoomLevel object for each WMTS zoom level in file
    private val zoomLevelMap: MutableMap<Long, TPKZoomLevel> = HashMap()

    /** the image format used in the TPK (eg JPEG, PNG); individual tiles are stored in this format */
    var imageFormat: String? = null
        private set

    // maps conf.xml//CacheInfo/TileCacheInfo/LODInfos/LODInfo/LevelID values to actual Web Map
    // Tile Service zoom levels (only used in initial open of TPK)
    private val zoomLevelMapping: MutableMap<Long, Long> = HashMap()

    // maps WMTS zoom level to its resolution (only used in initial open of TPK)
    private val zoomLevelResolutionMap: MutableMap<Long, Double> = HashMap()

    // support the original compact cache format as well as the new one
    enum class CacheType {
        V1, // bundle data and index files are separate
        V2 // index is included within the bundle data file and is different format
    }

    var cacheType: CacheType? = null
        private set

    /**
     * Used for first open of a TPK file: parses the conf.xml file and builds the zoomLevelMap
     */
    init {
        try {
            openTPK(theFile)

            // find the "conf.xml" file
            val xmlConf = zipEntryMap.keys.firstOrNull {
                it != null && (it == "conf.xml" || it.endsWith(CONFIGURATION_FILE))
            } ?: throw RuntimeException("conf.xml not found in TPK file")

            // extract metadata we need from the conf.xml file of the TPK
            parseConfigurationFile(zipEntryMap[xmlConf]!!)

            // create a TPKZoomLevel object for each zoom level
            loadZoomLevels()
        } catch (e: Exception) {
            close() // don't leak the open zip if the file turns out to be unusable
            throw e
        }
    }

    // ***********************************************************************
    // **   Public Methods                                                  **
    // ***********************************************************************
    fun getZoomLevelMap(): MutableMap<Long, TPKZoomLevel> = zoomLevelMap

    /** Called to close the TPK file and release resources being held */
    fun close() {
        zoomLevelMap.values.forEach { it.releaseResources() }
        zipEntryMap.clear()
        try {
            theTPK?.close()
        } catch (ex: IOException) {
            // don't care
        }
    }

    /** the minimum Web Tile Service zoom level contained in the TPK */
    val minZoomLevel: Long
        get() = zoomLevelMap.keys.min()

    /** the maximum Web Tile Service zoom level contained in the TPK */
    val maxZoomLevel: Long
        get() = zoomLevelMap.keys.max()

    /**
     * Find the closest zoom level in the TPK to the given zoom level
     *
     * @return the given zoom level or the nearest one to it
     */
    fun getClosestZoom(zoomLevel: Long): Long {
        val zooms = zoomLevelMap.keys
        if (zoomLevel in zooms) return zoomLevel
        var smallestDiff = Long.MAX_VALUE
        var closestZoom = 0L
        for (zl in zooms) {
            val diff = abs(zoomLevel - zl)
            if (diff < smallestDiff) {
                smallestDiff = diff
                closestZoom = zl
            }
        }
        return closestZoom
    }

    /** Minimum Web Tile Map Service column number for the zoom level, or -1 if not in the TPK */
    fun getMinColumn(zoomLevel: Long): Long = zoomLevelMap[zoomLevel]?.minColumn ?: -1

    /** Maximum Web Tile Map Service column number for the zoom level, or -1 if not in the TPK */
    fun getMaxColumn(zoomLevel: Long): Long = zoomLevelMap[zoomLevel]?.maxColumn ?: -1

    /** Minimum Web Tile Map Service row number for the zoom level, or -1 if not in the TPK */
    fun getMinRow(zoomLevel: Long): Long = zoomLevelMap[zoomLevel]?.minRow ?: -1

    /** Maximum Web Tile Map Service row number for the zoom level, or -1 if not in the TPK */
    fun getMaxRow(zoomLevel: Long): Long = zoomLevelMap[zoomLevel]?.maxRow ?: -1

    /**
     * Return a list of tile objects, each with its intended location, format and raw data
     *
     * @param top topmost row of tiles (latitude)
     * @param bottom bottommost row of tiles
     * @param left leftmost column of tiles (longitude)
     * @param right rightmost column of tiles
     * @param format format to interpret tile data (PNG, JPEG)
     * @return list of TPKTile objects, or null if the zoom level isn't in the TPK
     */
    fun getTiles(
        zoomLevel: Long, top: Long, bottom: Long, left: Long, right: Long, format: String?
    ): MutableList<TPKTile?>? = zoomLevelMap[zoomLevel]?.getTiles(top, bottom, left, right, format)

    // ***********************************************************************
    // **   Private Methods                                                 **
    // ***********************************************************************
    /** Open the TPK file and map the ZIPEntries by their path/filename */
    private fun openTPK(theFile: File) {
        val zip = try {
            ZipFile(theFile)
        } catch (ex: IOException) {
            throw RuntimeException("Unable to open TPK file", ex)
        }
        theTPK = zip

        // build a map of file path/name -> ZipEntry
        val zipEntries = zip.entries()
        while (zipEntries.hasMoreElements()) {
            val entry = zipEntries.nextElement()
            zipEntryMap[entry.name] = entry
        }
    }

    private fun firstElementText(doc: Document, tag: String): String =
        doc.getElementsByTagName(tag).item(0)?.textContent?.trim()
            ?: throw RuntimeException("Missing <$tag> element in conf.xml")

    /**
     * Extract required metadata from conf.xml: populates imageFormat, cacheType, zoomLevelMapping
     * and zoomLevelResolutionMap
     */
    private fun parseConfigurationFile(confFile: ZipEntry) {
        try {
            theTPK!!.getInputStream(confFile).use { input ->
                val confDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input)

                // "StorageFormat" determines compact cache version/format
                cacheType = when (firstElementText(confDoc, TAG_STORAGE_FORMAT)) {
                    COMPACT_CACHE_V1 -> CacheType.V1
                    COMPACT_CACHE_V2 -> CacheType.V2
                    else -> throw RuntimeException("Unknown value for StorageFormat element")
                }

                // format of each tile stored in the cache
                imageFormat = firstElementText(confDoc, TAG_TILE_FORMAT)

                // zoom-level information elements
                val lods = confDoc.getElementsByTagName(TAG_LOD_INFO)
                for (i in 0 until lods.length) parseLodInfo(lods.item(i))
            }
        } catch (ex: Exception) {
            throw RuntimeException("Caught exception opening/processing conf.xml", ex)
        }
    }

    /**
     * Parse LODInfo/LevelId and LODInfo/Resolution in order to map the nominal LevelID value
     * to an actual zoom level
     */
    private fun parseLodInfo(lodInfo: Node) {
        if (lodInfo.nodeType != Node.ELEMENT_NODE) return
        val lod = lodInfo as Element
        val levelText = lod.getElementsByTagName(TAG_LEVEL_ID).item(0)?.textContent?.trim() ?: return
        val resolutionText = lod.getElementsByTagName(TAG_RESOLUTION).item(0)?.textContent?.trim() ?: return

        val level = levelText.toLong()
        val resolution = resolutionText.toDouble()
        val zoomLevel = log2(WORLD_CIRCUMFERENCE / (resolution * TILE_PIXEL_SIZE)).roundToLong()
        zoomLevelMapping[level] = zoomLevel
        zoomLevelResolutionMap[zoomLevel] = resolution
    }

    private fun log2(number: Double): Double = ln(number) / ln(2.0)

    /**
     * Iterate over the zoom levels contained in the TPK archive and build a TPKZoomLevel object
     * for each. The object caches control information about the zoom level and each bundle that
     * comprises it; access to individual tile data is done via this object.
     */
    private fun loadZoomLevels() {
        val allNames = zipEntryMap.keys.filterNotNull()
        for ((levelId, zoomLevel) in zoomLevelMapping) {
            // "LevelID" folder
            val levelFolder = String.format(LEVEL_FOLDER, levelId)

            // names of all bundles for level
            val bundles = allNames.filter { it.contains(levelFolder) && it.endsWith(BUNDLE_DATA_EXTENSION) }
            if (bundles.isEmpty()) continue

            // build a zoom level object using the related bundles and bundle-indexes
            zoomLevelMap[zoomLevel] = when (cacheType) {
                CacheType.V1 -> {
                    // V2 caches don't have independent indexes
                    val indexes = allNames.filter {
                        it.contains(levelFolder) && it.endsWith(BUNDLE_INDEX_EXTENSION)
                    }
                    TPKZoomLevelV1(theTPK, zipEntryMap, bundles, indexes, zoomLevel)
                }
                CacheType.V2 -> TPKZoomLevelV2(theTPK, zipEntryMap, bundles, zoomLevel)
                null -> throw RuntimeException("Cache type was not determined")
            }
        }
    }

    companion object {
        // circumference of the earth in metres at the equator
        private const val WORLD_CIRCUMFERENCE = 40075016.69

        // in a web map service tileset all tiles are 256x256 pixels
        private const val TILE_PIXEL_SIZE: Long = 256

        // parsing the conf.xml file
        private const val CONFIGURATION_FILE = "/conf.xml"
        private const val TAG_STORAGE_FORMAT = "StorageFormat"
        private const val TAG_LOD_INFO = "LODInfo"
        private const val TAG_TILE_FORMAT = "CacheTileFormat"
        private const val TAG_LEVEL_ID = "LevelID"
        private const val TAG_RESOLUTION = "Resolution"

        // support for compact cache v1 and v2!!
        private const val COMPACT_CACHE_V1 = "esriMapCacheStorageModeCompact"
        private const val COMPACT_CACHE_V2 = "esriMapCacheStorageModeCompactV2"

        // finding zoom levels and their bundles/indexes
        private const val LEVEL_FOLDER = "_alllayers/L%02d/"
        private const val BUNDLE_DATA_EXTENSION = ".bundle"
        private const val BUNDLE_INDEX_EXTENSION = ".bundlx"
    }

    /**
     * Reads the raw (still compressed) bytes of one tile, or null if the zoom level or tile is not
     * in the package. Blocking; call from an IO dispatcher. Safe to call from several threads.
     */
    fun readTileData(zoom: Int, column: Int, row: Int): ByteArray? =
        getTiles(zoom.toLong(), row.toLong(), row.toLong(), column.toLong(), column.toLong(), null)
            ?.firstOrNull()?.tileData
}