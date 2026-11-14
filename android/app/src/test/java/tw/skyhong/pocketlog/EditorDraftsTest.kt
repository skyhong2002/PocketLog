package tw.skyhong.pocketlog

import org.junit.Assert.*
import org.junit.Test

class EditorDraftsTest {
    @Test fun intentionalEmptyEditIsDifferentFromNoDraftAndCannotEraseNewerTyping() {
        val drafts = EditorDrafts(2, 100)
        assertNull(drafts["day"])
        drafts.put("day", "")
        assertEquals("", drafts["day"])
        drafts.put("day", "newer")
        drafts.removeIfUnchanged("day", "submitted")
        assertEquals("newer", drafts["day"])
        drafts.removeIfUnchanged("day", "newer")
        assertNull(drafts["day"])
    }
    @Test fun draftsAreBoundedByCountAndUnicodeCodePointsAndClearOnReset() {
        val drafts = EditorDrafts(2, 2)
        drafts.put("a", "😀中文")
        assertEquals("😀中", drafts["a"])
        drafts.put("b", "b"); drafts.put("a", "a"); drafts.put("c", "c")
        assertNull(drafts["b"])
        assertEquals("a", drafts["a"])
        drafts.clear()
        assertNull(drafts["a"]); assertNull(drafts["c"])
    }
}
