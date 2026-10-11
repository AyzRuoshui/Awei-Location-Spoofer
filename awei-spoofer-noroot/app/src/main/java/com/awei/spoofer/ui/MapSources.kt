package com.awei.spoofer.ui

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.util.MapTileIndex
import java.util.Locale

/**
 * 地图瓦片源：
 * - 高德：国内直连快（默认），但底图内容为 GCJ-02，相对真实坐标偏移数百米
 * - ArcGIS：WGS84 无偏移，国内可达性中等
 * - OSM：官方源国内基本不可达，备用
 */
object MapSources {
    const val GAODE = 0
    const val ARCGIS = 1
    const val OSM = 2

    private val gaode: ITileSource = object : OnlineTileSourceBase(
        "高德街道", 0, 18, 256, ".png",
        arrayOf(
            "https://webrd01.is.autonavi.com", "https://webrd02.is.autonavi.com",
            "https://webrd03.is.autonavi.com", "https://webrd04.is.autonavi.com"
        )
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            String.format(
                Locale.US,
                "%s/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x=%d&y=%d&z=%d",
                baseUrl,
                MapTileIndex.getX(pMapTileIndex), MapTileIndex.getY(pMapTileIndex),
                MapTileIndex.getZoom(pMapTileIndex)
            )
    }

    private val arcgis: ITileSource = object : OnlineTileSourceBase(
        "ArcGIS街道", 0, 19, 256, ".png",
        arrayOf("https://server.arcgisonline.com")
    ) {
        // ArcGIS 路径顺序为 z/y/x
        override fun getTileURLString(pMapTileIndex: Long): String =
            String.format(
                Locale.US,
                "%s/ArcGIS/rest/services/World_Street_Map/MapServer/tile/%d/%d/%d",
                baseUrl,
                MapTileIndex.getZoom(pMapTileIndex), MapTileIndex.getY(pMapTileIndex),
                MapTileIndex.getX(pMapTileIndex)
            )
    }

    fun forMode(mode: Int): ITileSource = when (mode) {
        ARCGIS -> arcgis
        OSM -> TileSourceFactory.MAPNIK
        else -> gaode
    }
}
