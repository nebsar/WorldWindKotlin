package earth.worldwind.layer.sqlite

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import earth.worldwind.render.image.ImageSource
import earth.worldwind.util.ResourcePostprocessor
import java.io.ByteArrayOutputStream

open class SqliteImageFactory(
    protected val store: SqliteTileStore,
    protected val isReadOnly: Boolean,
    protected val contentKey: String,
    protected val zoom: Int,
    protected val column: Int,
    protected val row: Int,
    protected val zoomInverted: Boolean,
    protected val format: Bitmap.CompressFormat,
    protected val quality: Int = 100
) : ImageSource.ImageFactory, ResourcePostprocessor {
    override suspend fun createBitmap(): Bitmap? {
        val data = store.readTile(zoom, column, row, zoomInverted) ?: return null
        if (data.isEmpty()) return null
        return BitmapFactory.decodeByteArray(data, 0, data.size)
    }

    override suspend fun <Resource> process(resource: Resource): Resource {
        if (resource is Bitmap && !isReadOnly) {
            val stream = ByteArrayOutputStream()
            resource.compress(format, quality, stream)
            store.writeTile(zoom, column, row, zoomInverted, stream.toByteArray())
        }
        return resource
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SqliteImageFactory) return false
        return store === other.store && contentKey == other.contentKey && zoom == other.zoom && column == other.column && row == other.row
    }

    override fun hashCode(): Int {
        var result = System.identityHashCode(store)
        result = 31 * result + contentKey.hashCode()
        result = 31 * result + zoom
        result = 31 * result + column
        result = 31 * result + row
        return result
    }

    override fun toString() = "SqliteImageFactory(contentKey=$contentKey, zoom=$zoom, column=$column, row=$row)"
}