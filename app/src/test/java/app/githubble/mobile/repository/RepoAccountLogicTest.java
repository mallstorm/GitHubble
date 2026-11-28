package app.githubble.mobile.repository;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RepoAccountLogicTest {
    @Test public void repositoriesAreParsed() throws Exception {
        List<RepoList.Item> items = RepoList.parse(new JSONArray("[{\"full_name\":\"o/a\",\"private\":true,\"fork\":false,\"archived\":false,\"permissions\":{\"admin\":true}},"
            + "{\"full_name\":\"o/b\",\"archived\":true,\"fork\":true},{\"full_name\":\"bad name\"},{}]"));
        assertEquals(2, items.size());
        assertTrue(items.get(0).priv); assertTrue(items.get(0).admin); assertEquals("private", items.get(0).tags());
        assertTrue(items.get(1).archived); assertFalse(items.get(1).admin); assertTrue(items.get(1).tags().contains("fork")); assertTrue(items.get(1).tags().contains("архив"));
    }

    @Test public void dependabotSeveritiesAreCounted() throws Exception {
        java.lang.reflect.Constructor<SecuritySummary.Row> c = SecuritySummary.Row.class.getDeclaredConstructor(String.class);
        c.setAccessible(true);
        SecuritySummary.Row row = c.newInstance("o/a");
        SecuritySummary.countDependabot(row, new JSONArray("[{\"security_advisory\":{\"severity\":\"critical\"}},{\"security_advisory\":{\"severity\":\"high\"}},{\"security_advisory\":{\"severity\":\"high\"}},"
            + "{\"security_advisory\":{\"severity\":\"medium\"}},{\"security_advisory\":{\"severity\":\"low\"}},{}]"));
        assertEquals(1, row.critical); assertEquals(2, row.high); assertEquals(1, row.medium); assertEquals(2, row.low); assertEquals(6, row.dependabotTotal());
        row.secret = 1;
        assertEquals(1000 + 200 + 10 + 2 + 500, row.weight()); assertFalse(row.clean());
        assertTrue(row.line().contains("1 крит.")); assertTrue(row.line().contains("Секреты: 1"));
        row.codeScanning = false; row.dependabot = false;
        assertTrue(row.line().contains("нет доступа")); assertTrue(row.line().contains("Code: —"));
        SecuritySummary.Row empty = c.newInstance("o/b");
        assertTrue(empty.clean()); assertTrue(empty.line().startsWith("Dependabot: 0"));
    }

    @Test public void bulkReportListsFailures() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("o/a", Arrays.<String>asList());
        result.put("o/b", Arrays.asList("Включить secret scanning: недоступно"));
        String text = BulkSettings.report(result);
        assertTrue(text.startsWith("Готово: 1 из 2, с ошибками: 1")); assertTrue(text.contains("✗ o/b")); assertTrue(text.contains("недоступно"));
        assertEquals("Готово: 1 из 1", BulkSettings.report(Map.of("o/a", Arrays.<String>asList())));
        for (BulkSettings.Action action : BulkSettings.Action.values()) { assertFalse(action.label.isEmpty()); assertFalse(action.permission.isEmpty()); }
    }
}
