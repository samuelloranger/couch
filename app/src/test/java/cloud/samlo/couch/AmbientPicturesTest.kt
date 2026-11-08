package cloud.samlo.couch

import cloud.samlo.couch.ui.nextAmbientPicture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmbientPicturesTest {
    @Test fun advancesWithinTheSameSessionAndWrapsAtTheEnd() {
        val pictures = listOf("a.jpg", "b.jpg", "c.jpg")
        assertEquals("b.jpg", nextAmbientPicture(pictures, "a.jpg"))
        assertEquals("c.jpg", nextAmbientPicture(pictures, "b.jpg"))
        assertEquals("a.jpg", nextAmbientPicture(pictures, "c.jpg"))
    }

    @Test fun picksUpAnImageAddedAfterTheDisplayedOne() {
        assertEquals("new.jpg", nextAmbientPicture(listOf("a.jpg", "new.jpg"), "a.jpg"))
    }

    @Test fun fileInsertedBeforeTheCurrentOneDoesNotRepeatIt() {
        assertEquals("c.jpg", nextAmbientPicture(listOf("new.jpg", "a.jpg", "b.jpg", "c.jpg"), "b.jpg"))
    }

    @Test fun removedCurrentFileStartsAtTheFirstRemainingImage() {
        assertEquals("b.jpg", nextAmbientPicture(listOf("b.jpg", "c.jpg"), "a.jpg"))
    }

    @Test fun aSingleFileStaysSelectedAndAnEmptyGalleryHasNoNextFile() {
        assertEquals("a.jpg", nextAmbientPicture(listOf("a.jpg"), "a.jpg"))
        assertNull(nextAmbientPicture(emptyList(), "a.jpg"))
    }
}
