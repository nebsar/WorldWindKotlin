package earth.worldwind.layer.sqlite

import android.graphics.Bitmap
import android.os.Build
import earth.worldwind.render.image.ImageSource

actual fun buildSqliteImageSource(
    store: SqliteTileStore, readOnly: Boolean, contentKey: String,
    zoom: Int, column: Int, row: Int, imageFormat: String, zoomInverted: Boolean
): ImageSource {
    val format = when {
        imageFormat.equals("image/jpeg", true) -> Bitmap.CompressFormat.JPEG
        imageFormat.equals("image/png", true) -> Bitmap.CompressFormat.PNG
        imageFormat.equals("image/webp", true) -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSLESS
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        else -> Bitmap.CompressFormat.PNG
    }
    return ImageSource.fromImageFactory(
        SqliteImageFactory(store, readOnly, contentKey, zoom, column, row, zoomInverted, format)
    )
}