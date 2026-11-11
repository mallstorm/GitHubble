package app.githubble.mobile.agent;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class ToolCallExtractorTest {
    @Test public void findsToolsAfterProse() throws Exception {
        String raw = "В репозитории только README. Прочитаю его.\n{\"tools\":[{\"name\":\"repository_read_file\",\"args\":{\"repository\":\"o/r\",\"path\":\"README.md\"}},{\"name\":\"import_repository\",\"args\":{\"repository\":\"o/r\"}}]}";
        JSONObject found = ToolCallExtractor.find(raw);
        assertNotNull(found);
        assertEquals(2, found.getJSONArray("tools").length());
    }

    @Test public void findsToolsInsideCodeFence() {
        String raw = "```json\n{\"tools\":[{\"name\":\"list_repositories\",\"args\":{}}]}\n```";
        assertNotNull(ToolCallExtractor.find(raw));
    }

    @Test public void bracesInsideStringsDoNotBreakMatching() throws Exception {
        String raw = "text {\"tools\":[{\"name\":\"search_files\",\"args\":{\"query\":\"}{ \\\" {\"}}]} tail";
        JSONObject found = ToolCallExtractor.find(raw);
        assertNotNull(found);
        assertEquals("search_files", found.getJSONArray("tools").getJSONObject(0).getString("name"));
    }

    @Test public void plainProseAndAnswerObjectsAreNotToolCalls() {
        assertNull(ToolCallExtractor.find("Готово. Репозиторий создан."));
        assertNull(ToolCallExtractor.find("{\"answer\":\"ok\"}"));
        assertNull(ToolCallExtractor.find("{\"tools\":[]}"));
        assertNull(ToolCallExtractor.find("{\"tools\":[{\"name\":\"x\""));
        assertNull(ToolCallExtractor.find(null));
    }
}
