package app.githubble.mobile.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class InboxItemTest {
    @Test public void threadIsParsed() throws Exception{
        JSONObject thread=new JSONObject("{\"id\":\"42\",\"unread\":true,\"reason\":\"review_requested\","
                +"\"updated_at\":\"2026-11-12T10:00:00Z\",\"subject\":{\"title\":\"Fix\",\"type\":\"PullRequest\","
                +"\"url\":\"https://api.github.com/repos/a/b/pulls/17\"},\"repository\":{\"full_name\":\"a/b\"}}");
        InboxItem item=InboxItem.fromThread(thread);
        assertEquals("a/b",item.repo);assertEquals(17,item.number);assertTrue(item.unread);
        assertEquals("Просят ревью",item.reasonText());assertEquals("42",item.threadId);
    }

    @Test public void brokenThreadIsIgnored() throws Exception{
        assertNull(InboxItem.fromThread(new JSONObject("{\"id\":\"1\"}")));
        assertNull(InboxItem.fromThread(null));
    }

    @Test public void searchItemsMergeReasons() throws Exception{
        JSONObject issue=new JSONObject("{\"number\":5,\"title\":\"T\",\"updated_at\":\"2026-11-12T10:00:00Z\","
                +"\"repository_url\":\"https://api.github.com/repos/a/b\",\"pull_request\":{}}");
        JSONObject older=new JSONObject("{\"number\":6,\"title\":\"O\",\"updated_at\":\"2026-11-01T10:00:00Z\","
                +"\"repository_url\":\"https://api.github.com/repos/a/b\"}");
        List<InboxItem> all=new ArrayList<>();
        all.add(InboxItem.fromSearch(older,"Назначено вам"));
        all.add(InboxItem.fromSearch(issue,"Просят ревью"));
        all.add(InboxItem.fromSearch(issue,"Упоминание"));
        List<InboxItem> merged=InboxService.merge(all);
        assertEquals(2,merged.size());
        assertEquals("a/b#5",merged.get(0).key);
        assertEquals("PullRequest",merged.get(0).type);
        assertEquals("Просят ревью, Упоминание",merged.get(0).reasonText());
    }

    @Test public void queriesUseLogin(){
        assertTrue(InboxService.searchQueries("octo")[0][0].contains("review-requested:octo"));
        assertEquals(0,InboxItem.numberFromUrl("https://x/y/"));
    }
}
