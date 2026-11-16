package earth.worldwind.layer.tpk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import earth.worldwind.render.image.ImageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android image factory for one TPK tile: reads the raw tile bytes from the package and decodes
 * them with [BitmapFactory] (which detects JPEG/PNG itself, so MIXED packages work too).
 *
 * The package is read-only, so unlike MBTilesImageFactory there is nothing to write back.
 */
open class TPKImageFactory(
    protected val tpkFile: TPKFile,
    protected val zoom: Int,
    protected val column: Int,
    protected val row: Int,
    protected val imageFormat: String?
) : ImageSource.ImageFactory {
    override suspend fun createBitmap(): Bitmap? {
        // Blocking zip/bundle reads: keep them off whatever dispatcher the retriever uses.
        val data = withContext(Dispatchers.IO) { tpkFile.readTileData(zoom, column, row) } ?: return null

        // JPEG tiles carry no alpha, so half the memory per tile is enough.
        val options = if (imageFormat.equals("image/jpeg", true)) {
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        } else null
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)
    }

    // Image sources are compared to de-duplicate/cache retrievals, so two different packages must
    // never be equal even for the same zoom/column/row: the TPKFile instance is the identity.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TPKImageFactory) return false
        return tpkFile === other.tpkFile && zoom == other.zoom && column == other.column && row == other.row
    }

    override fun hashCode(): Int {
        var result = System.identityHashCode(tpkFile)
        result = 31 * result + zoom
        result = 31 * result + column
        result = 31 * result + row
        return result
    }

    override fun toString() = "TPKImageFactory(zoom=$zoom, column=$column, row=$row)"
}