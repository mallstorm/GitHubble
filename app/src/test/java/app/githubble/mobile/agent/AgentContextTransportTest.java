package app.githubble.mobile.agent;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import static org.junit.Assert.*;
public final class AgentContextTransportTest {
    @Test public void apiTransportPreservesToolResultsPastOldTwentyThousandLimit()throws Exception{
        Method m=OmniChatChatClient.class.getDeclaredMethod("conversation",JSONArray.class);m.setAccessible(true);
        String body="x".repeat(21000)+"VERIFIED_TOOL_RESULT";
        JSONArray out=(JSONArray)m.invoke(null,new JSONArray().put(new JSONObject().put("role","user").put("body",body)));
        assertEquals(body,out.getJSONObject(0).getString("content"));
    }
    @Test public void tooLargeContextFailsExplicitlyInsteadOfSilentlyChangingTask()throws Exception{
        Method m=OmniChatChatClient.class.getDeclaredMethod("conversation",JSONArray.class);m.setAccessible(true);
        try{
            m.invoke(null,new JSONArray().put(new JSONObject().put("role","user").put("body","x".repeat(80001))));
            fail("Expected an explicit size error");
        }catch(InvocationTargetException expected){assertTrue(expected.getCause() instanceof IllegalArgumentException);}
    }
}
