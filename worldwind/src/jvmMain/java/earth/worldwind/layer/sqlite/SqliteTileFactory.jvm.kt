package earth.worldwind.layer.sqlite

import earth.worldwind.render.image.ImageSource

actual class SqliteTileStore actual constructor(
    contentPath: String,
    readOnly: Boolean
) {
    actual val isOpen: Boolean
        get() = TODO("Not yet implemented")

    actual fun hasTileTable(): Boolean {
        TODO("Not yet implemented")
    }

    actual fun infoValue(key: String): String? {
        TODO("Not yet implemented")
    }

    actual fun readTile(
        zoom: Int,
        column: Int,
        row: Int,
        zoomInverted: Boolean
    ): ByteArray? {
        TODO("Not yet implemented")
    }

    actual fun writeTile(
        zoom: Int,
        column: Int,
        row: Int,
        zoomInverted: Boolean,
        data: ByteArray
    ) {
    }

    actual fun clearTiles() {
    }

    actual fun close() {
    }
}

actual fun buildSqliteImageSource(
    store: SqliteTileStore,
    readOnly: Boolean,
    contentKey: String,
    zoom: Int,
    column: Int,
    row: Int,
    imageFormat: String,
    zoomInverted: Boolean
): ImageSource {
    TODO("Not yet implemented")
}