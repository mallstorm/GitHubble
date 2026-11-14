package app.githubble.mobile.agent;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class EnvelopeTransportTest {
    @Test public void mixedProseAndFencedToolsRemainCompatible() throws Exception {
        ModelTransport.Turn t=new EnvelopeTransport(p->"I will inspect.\n```json\n{\"tools\":[{\"name\":\"read_file\",\"args\":{\"path\":\"a.java\"}}]}\n```").complete("task",new JSONArray(),new JSONArray());
        assertEquals("read_file",t.calls.getJSONObject(0).getString("name"));assertEquals("",t.answer);
    }
    @Test public void proseAndJsonAnswersDoNotRequireTools() throws Exception {
        assertEquals("hello",new EnvelopeTransport(p->"hello").complete("task",new JSONArray(),new JSONArray()).answer);
        assertEquals("done",new EnvelopeTransport(p->"{\"answer\":\"done\"}").complete("task",new JSONArray(),new JSONArray()).answer);
    }
    @Test public void compactionPreservesWholeJsonRecords() throws Exception {
        JSONArray records=new JSONArray().put(new JSONObject().put("data","x".repeat(500))).put(new JSONObject().put("data","latest"));
        String compact=AgentContextBuilder.compact(records,100);JSONArray parsed=new JSONArray(compact);assertTrue(compact.contains("latest"));assertEquals(1,parsed.getJSONObject(0).getInt("omittedOlderResults"));
    }
}
