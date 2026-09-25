package ai.recommend.spacegallery.ml

import ai.recommend.spacegallery.ml.face.FaceDetector
import ai.recommend.spacegallery.ml.face.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Поиск лиц по частям кадра — им добираются лица в толпе. Геометрию проверяем без модели:
 * важно, чтобы кадр покрывался целиком и чтобы лицо на стыке целиком попадало хотя бы
 * в одну часть.
 */
class FaceTilingTest {

    @Test
    fun tilesCoverTheWholeFrame() {
        val width = 2560
        val height = 1707
        val tiles = FaceDetector.tileRects(width, height, grid = 3)

        assertEquals(9, tiles.size)
        assertTrue(
            "Часть вышла за кадр",
            tiles.all { it.left >= 0 && it.top >= 0 && it.right <= width && it.bottom <= height },
        )
        // Каждая точка кадра лежит хотя бы в одной части — иначе там лица не ищутся вовсе.
        for (x in 0 until width step 37) for (y in 0 until height step 41) {
            assertTrue("Точка ($x, $y) не покрыта", tiles.any { it.contains(x, y) })
        }
    }

    @Test
    fun faceOnTheSeamFitsEntirelyIntoSomeTile() {
        val tiles = FaceDetector.tileRects(2000, 2000, grid = 2)
        // Лицо ровно на стыке частей: 120 px вокруг середины кадра.
        val face = Tile(940, 940, 1060, 1060)

        assertTrue("Лицо на стыке разрезано между частями", tiles.any { it.contains(face) })
    }

    @Test
    fun tinyFrameIsNotSplitIntoUselessPieces() {
        // Мелкий кадр резать незачем: части были бы меньше входа модели.
        assertTrue(FaceDetector.tileRects(100, 80, grid = 3).isEmpty())
        assertEquals(1, FaceDetector.tileRects(100, 80, grid = 1).size)
    }
}
