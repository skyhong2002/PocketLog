package tw.skyhong.pocketlog

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AgentImportTest {
    private val now = Instant.parse("2026-11-14T10:00:00Z").toEpochMilli()
    @Test fun onlyExplicitTextBlocksBecomePreviewCandidates() {
        val stamp = Instant.ofEpochMilli(now - 1000).toString()
        val codex = listOf(
            """{"type":"session_meta","timestamp":"$stamp","payload":{"cwd":"NEVER_IMPORT"}}""",
            """{"type":"response_item","timestamp":"$stamp","payload":{"type":"function_call_output","output":"NEVER_IMPORT"}}""",
            """{"type":"response_item","timestamp":"$stamp","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"synthetic answer"},{"type":"image","url":"NEVER_IMPORT"}]}}"""
        ).joinToString("\n")
        val rows=AgentImport.parse(codex.toByteArray(),"codex",now)
        assertEquals(1,rows.length());assertEquals("synthetic answer",rows.getJSONObject(0).getString("text"));assertFalse(rows.toString().contains("NEVER_IMPORT"))
        val claude="""{"type":"assistant","timestamp":"$stamp","message":{"role":"assistant","content":[{"type":"thinking","thinking":"NEVER_IMPORT"},{"type":"tool_use","input":"NEVER_IMPORT"},{"type":"text","text":"${"😀".repeat(1100)}"}]}}"""
        val text=AgentImport.parse(claude.toByteArray(),"claude",now).getJSONObject(0).getString("text")
        assertEquals(1000,text.codePointCount(0,text.length));assertFalse(text.contains("NEVER_IMPORT"))
    }
    @Test fun rejectsLargeFilesAndSkipsExpiredOrMissingTimestamps() {
        assertTrue(runCatching{AgentImport.parse(ByteArray(524289),"codex",now)}.isFailure)
        assertTrue(runCatching{AgentImport.parse("{}\n".repeat(2001).toByteArray(),"claude",now)}.isFailure)
        val expired=JSONObject().put("type","user").put("timestamp","2020-01-01T00:00:00Z").put("message",JSONObject().put("role","user").put("content","expired"))
        assertEquals(0,AgentImport.parse(expired.toString().toByteArray(),"claude",now).length())
    }
}
