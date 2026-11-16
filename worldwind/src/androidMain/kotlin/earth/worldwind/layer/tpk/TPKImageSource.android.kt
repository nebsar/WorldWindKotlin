package earth.worldwind.layer.tpk

import earth.worldwind.render.image.ImageSource

actual fun buildImageSource(
    tpkFile: TPKFile, zoom: Int, column: Int, row: Int, imageFormat: String?,
): ImageSource = ImageSource.fromImageFactory(TPKImageFactory(tpkFile, zoom, column, row, imageFormat))
