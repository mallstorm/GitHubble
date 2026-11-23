package app.githubble.mobile.repository;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.HashSet;
import java.util.Set;

public final class RepositoryMetadataBackupTest {
    @Test public void recognizesGitHubIssuesAndExcludesPullRequests() throws Exception {
        JSONArray mixed=new JSONArray()
                .put(new JSONObject().put("number",12).put("title","Task"))
                .put(new JSONObject().put("number",13).put("title","Review")
                        .put("pull_request",new JSONObject().put("url","https://api.github.com/example")));
        JSONArray filtered=RepositoryMetadataBackup.cleanIssues(mixed);
        assertEquals(1,filtered.length());
        assertEquals(12,filtered.getJSONObject(0).getInt("number"));
    }
    @Test public void avoidsDuplicateLabelAndMilestoneNamesCaseInsensitive() throws Exception {
        JSONArray existing=new JSONArray().put(new JSONObject().put("name","Bug"));
        Set<String> names=RepositoryMetadataBackup.names(existing,"name");
        JSONArray archive=new JSONArray()
                .put(new JSONObject().put("name","bug"))
                .put(new JSONObject().put("name","Feature"))
                .put(new JSONObject().put("name","feature"))
                .put(new JSONObject().put("name","Docs"));
        assertEquals(2,RepositoryMetadataBackup.countNew(archive,names,"name"));
        assertTrue(names.contains("bug"));
        assertTrue(names.contains("feature"));
        assertTrue(names.contains("docs"));
    }
    @Test public void rejectsEmptyNameRatherThanCreatingBrokenPartialRecords() throws Exception {
        try {
            RepositoryMetadataBackup.countNew(
                    new JSONArray().put(new JSONObject().put("title","")),
                    new HashSet<>(),"title");
            fail("Empty title accepted");
        }catch(SecurityException expected) { }
    }
}
