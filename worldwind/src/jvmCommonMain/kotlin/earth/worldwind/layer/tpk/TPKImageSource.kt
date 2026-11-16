package earth.worldwind.layer.tpk

import earth.worldwind.render.image.ImageSource

/**
 * Platform-specific part of the TPK layer, mirroring `buildImageSource` in the MBTiles module.
 *
 * Returns an [ImageSource] that, when the tile is requested, reads the raw tile bytes with
 * [TPKFile.readTileData] (off the main thread) and decodes them into the platform's image type
 * (Bitmap on Android). Tiles missing from the package must fail/return no image rather than
 * throw, so WorldWind just leaves a gap.
 *
 * @param zoom absolute zoom level (WorldWind `Level.levelNumber`)
 * @param column tile column
 * @param row tile row, counted from the SOUTH like WorldWind tile rows; TPKFile already
 *            flips ESRI's top-down rows into this convention, so it must NOT be inverted again
 * @param imageFormat MIME type derived from conf.xml (e.g. "image/jpeg"), or null when the
 *            package declares a mixed/unknown format -- decoders should sniff the bytes
 */
expect fun buildImageSource(tpkFile: TPKFile, zoom: Int, column: Int, row: Int, imageFormat: String?): ImageSource