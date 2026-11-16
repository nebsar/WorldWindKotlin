package earth.worldwind.layer.tpk

import earth.worldwind.geom.Location
import earth.worldwind.layer.mercator.MercatorSector
import earth.worldwind.layer.mercator.MercatorTiledImageLayer
import earth.worldwind.layer.mercator.MercatorTiledSurfaceImage
import earth.worldwind.render.image.ImageConfig
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.util.LevelSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TPKLayerFactory {
    /**
     * Opens the .tpk at [pathName] and builds a Web Mercator image layer from it. Opening scans
     * the bundle indexes, so this runs on the IO dispatcher. Throws if the file is not a usable
     * TPK; the file is closed again in that case.
     */
    suspend fun createLayer(pathName: String) = withContext(Dispatchers.IO) {
        val tileFactory = TPKTileFactory(pathName)
        try {
            val tileOrigin = MercatorSector()
            val sector = MercatorSector().copy(tileFactory.boundingSector ?: tileOrigin)
            // Same level layout as the MBTiles layer: absolute level numbers up to maxZoom,
            // with the levels below minZoom skipped via levelOffset.
            val levelSet = LevelSet(
                sector = sector,
                tileOrigin = tileOrigin,
                firstLevelDelta = Location(tileOrigin.deltaLatitude, tileOrigin.deltaLongitude),
                numLevels = tileFactory.maxZoom + 1,
                tileWidth = 256,
                tileHeight = 256,
                levelOffset = tileFactory.minZoom
            )
            MercatorTiledImageLayer(tileFactory.contentKey, MercatorTiledSurfaceImage(tileFactory, levelSet).apply {
                if (tileFactory.isOpaque) imageOptions = ImageOptions(ImageConfig.RGB_565)
            })
        } catch (e: Exception) {
            tileFactory.shutdown()
            throw e
        }
    }
}