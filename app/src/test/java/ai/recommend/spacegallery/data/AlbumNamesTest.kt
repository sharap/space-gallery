package ai.recommend.spacegallery.data

import ai.recommend.spacegallery.data.media.AlbumNames
import ai.recommend.spacegallery.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumNamesTest {

    @Test
    fun renameKeepsParentFolder() {
        assertEquals("DCIM/Море/", AlbumNames.renamedPath("DCIM/Camera/", "Море"))
        assertEquals("Pictures/Screenshots 2/", AlbumNames.renamedPath("Pictures/Screenshots/", "  Screenshots   2 "))
        assertEquals("Download/a/Новое/", AlbumNames.renamedPath("Download/a/b/", "Новое"))
    }

    @Test
    fun rootFolderRenamesIntoPictures() {
        assertEquals("Pictures/Море/", AlbumNames.renamedPath("", "Море"))
        assertEquals("Pictures/Море/", AlbumNames.renamedPath("Pictures/", "Море"))
    }

    @Test
    fun newAlbumGoesToPictures() {
        assertEquals("Pictures/Отпуск 2026/", AlbumNames.newAlbumPath(" Отпуск  2026 "))
    }

    @Test
    fun validation() {
        assertTrue(AlbumNames.isValid("Отпуск 2026"))
        assertFalse(AlbumNames.isValid("   "))
        assertFalse(AlbumNames.isValid("a/b"))
        assertFalse(AlbumNames.isValid("what?"))
        assertFalse(AlbumNames.isValid(".."))
        assertFalse(AlbumNames.isValid("x".repeat(AlbumNames.MAX_LENGTH + 1)))
    }

    @Test
    fun allowedFoldersDependOnMediaType() {
        val photo = setOf(MediaType.IMAGE)
        val video = setOf(MediaType.VIDEO)
        val mixed = setOf(MediaType.IMAGE, MediaType.VIDEO)
        assertTrue(AlbumNames.canHold("DCIM/Camera/", mixed))
        assertTrue(AlbumNames.canHold("pictures/Отпуск/", photo)) // регистр не важен
        assertTrue(AlbumNames.canHold("Movies/Клипы/", video))
        assertFalse(AlbumNames.canHold("Movies/Клипы/", mixed))
        assertFalse(AlbumNames.canHold("sync/старье/", photo)) // случай с устройства
        assertFalse(AlbumNames.canHold("Download/", photo))
        assertFalse(AlbumNames.canHold("Android/media/com.whatsapp/WhatsApp/Media/", photo))
    }

    @Test
    fun renameOnlyInsideStandardRoots() {
        assertTrue(AlbumNames.canRename("DCIM/Camera/"))
        assertTrue(AlbumNames.canRename("Pictures/Screenshots/"))
        assertFalse(AlbumNames.canRename("sync/старье/"))
        assertFalse(AlbumNames.canRename(""))
    }
}
