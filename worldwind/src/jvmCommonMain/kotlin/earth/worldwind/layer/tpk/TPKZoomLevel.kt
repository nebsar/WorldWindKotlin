package earth.worldwind.layer.tpk

import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * A TPK file contains one or more zoom levels. This class is an abstraction
 * of a zoom level providing the coverage values of the zoom level (minColumn,
 * minRow, maxColumn, maxRow) and the ability to retrieve tiles for a particular
 * coverage in that zoom level.
 *
 * A zoom level can consist of multiple bundle/bundle index files; the zoomLevel
 * abstracts this away from the caller.
 */
abstract class TPKZoomLevel {
    /** bundles for this zoom level */
    val bundles: MutableList<TPKBundle> = ArrayList()

    /** max value for row and col (2**zoomLevel - 1) */
    var max_row_column: Long = 0
        protected set

    // on every subsequent use (after creation) the ZipFile and ZipEntry map must
    // be injected into each TPKZoomLevel concrete instance, this allows us to close
    // the zip file and release its resources between renders
    abstract fun setTPKandEntryMap(theTPK: ZipFile?, zipEntryMap: MutableMap<String?, ZipEntry?>?)

    // this is where the rubber meets the road
    abstract fun getTiles(
        top: Long, bottom: Long, left: Long, right: Long, format: String?
    ): MutableList<TPKTile?>?

    // for low memory footprint release resources after each render!
    abstract fun releaseResources()

    /** the zoom level */
    abstract val zoomLevel: Long

    /** minimum row in coverage at this zoom level */
    abstract val minRow: Long

    /** maximum row in coverage at this zoom level */
    abstract val maxRow: Long

    /** minimum column in coverage at this zoom level */
    abstract val minColumn: Long

    /** maximum column in coverage at this zoom level */
    abstract val maxColumn: Long

    /**
     * Index of the bundle that holds (col,row), trying [hint] first since consecutive
     * tiles usually live in the same bundle. Returns -1 if no bundle has that tile.
     */
    protected fun findBundleIndex(hint: Int, col: Long, row: Long): Int {
        if (hint in bundles.indices && bundles[hint].inBundle(col, row)) return hint
        return bundles.indexOfFirst { it.inBundle(col, row) }
    }
}