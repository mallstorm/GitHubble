package app.githubble.mobile.agent;

import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class OAuthSubscriptionsTest {
    private static final class Fixture implements OAuthSubscriptions.Transport {
        final Queue<OAuthSubscriptions.Reply> replies=new ArrayDeque<>();
        final List<String> urls=new ArrayList<>(),bodies=new ArrayList<>();
        final List<Map<String,String>> headers=new ArrayList<>();
        Fixture add(int code,String body){replies.add(new OAuthSubscriptions.Reply(code,body));return this;}
        public OAuthSubscriptions.Reply send(String url,String method,String body,boolean form,Map<String,String> h){
            urls.add(url);bodies.add(body);headers.add(h);return replies.remove();
        }
    }
    @Test public void kimiPendingAndSlowDownNeverBecomeConnected()throws Exception{
        Fixture f=new Fixture().add(200,"{\"user_code\":\"ABCD-EFGH\",\"device_code\":\"opaque\",\"verification_uri\":\"https://auth.kimi.ai/activate\",\"interval\":2}")
            .add(400,"{\"error\":\"authorization_pending\"}").add(429,"{\"error\":\"slow_down\"}")
            .add(200,"{\"access_token\":\"access\",\"refresh_token\":\"refresh\",\"expires_in\":3600}");
        OAuthSubscriptions c=new OAuthSubscriptions(f);OAuthSubscriptions.DeviceLogin login=c.begin(OAuthSubscriptions.KIMI);
        assertNull(c.poll(login));assertNull(c.poll(login));assertEquals(7000,login.interval);
        JSONObject session=c.poll(login);assertEquals("refresh",session.getString("refresh_token"));
        assertTrue(f.urls.get(1).startsWith("https://auth.kimi.ai/"));
        assertTrue(f.bodies.get(1).contains("device_code=opaque"));
    }
    @Test public void openaiVerifiesPkceBeforeExchangingCode()throws Exception{
        String verifier="a-verifier-for-this-attempt";
        String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        Fixture f=new Fixture().add(200,"{\"device_auth_id\":\"device\",\"user_code\":\"123456789\"}")
            .add(200,new JSONObject().put("authorization_code","code").put("code_verifier",verifier).put("code_challenge",challenge).toString())
            .add(200,"{\"access_token\":\"access\",\"refresh_token\":\"refresh\",\"expires_in\":3600}");
        OAuthSubscriptions c=new OAuthSubscriptions(f);OAuthSubscriptions.DeviceLogin d=c.begin(OAuthSubscriptions.OPENAI);
        assertEquals("123456789",d.userCode);assertEquals("https://auth.openai.com/codex/device",d.url);
        assertNotNull(c.poll(d));assertTrue(f.bodies.get(2).contains("code_verifier="));
        assertFalse(OAuthSubscriptions.pkceMatches(verifier,"wrong"));
    }
    @Test public void clineUsesRegisteredAccountAndVerifiedCatalogOnly()throws Exception{
        Fixture f=new Fixture().add(200,"{\"device_code\":\"device\",\"user_code\":\"CODE\",\"verification_uri\":\"https://cline.authkit.app/activate\"}")
            .add(200,"{\"access_token\":\"workos-access\",\"refresh_token\":\"workos-refresh\"}")
            .add(200,"{\"data\":{\"accessToken\":\"account-access\",\"refreshToken\":\"account-refresh\"}}")
            .add(200,"{\"data\":[{\"id\":\"model-a\"}]}")
            .add(200,"{\"clinePass\":[{\"id\":\"cline-pass/live-model\"}],\"free\":[\"live-free\"]}");
        OAuthSubscriptions c=new OAuthSubscriptions(f);JSONObject session=c.poll(c.begin(OAuthSubscriptions.CLINE));
        assertTrue(f.urls.get(2).endsWith("/auth/register"));
        assertEquals(Arrays.asList("live-free","cline-pass/live-model","model-a"),c.discover(OAuthSubscriptions.CLINE,session.toString()));
        assertEquals("Bearer workos:account-access",f.headers.get(3).get("Authorization"));
    }
    @Test public void refreshedSessionsRetainOwnerAndRotatedRefreshToken()throws Exception{
        Fixture f=new Fixture().add(200,"{\"access_token\":\"new-access\",\"refresh_token\":\"rotated\",\"expires_in\":3600}");
        JSONObject old=new JSONObject().put("provider",OAuthSubscriptions.KIMI).put("owner","account-A").put("refresh_token","old");
        JSONObject fresh=new OAuthSubscriptions(f).refresh(OAuthSubscriptions.KIMI,old);
        assertEquals("account-A",fresh.getString("owner"));assertEquals("rotated",fresh.getString("refresh_token"));
        assertTrue(f.urls.get(0).startsWith("https://auth.kimi.ai/"));
    }
    @Test public void streamsRequireCompletedEventAndRejectFailure()throws Exception{
        String stream="data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello\"}\r\n\r\n"
            +"data: {\"type\":\"response.completed\",\"response\":{}}\r\n\r\n";
        assertEquals("Hello",OAuthSubscriptions.parseResponses(stream));
        try{OAuthSubscriptions.parseResponses(stream.substring(0,stream.indexOf("data: {\"type\":\"response.completed")));fail();}catch(java.io.IOException expected){}
        try{OAuthSubscriptions.parseResponses("data: {\"type\":\"response.failed\"}\n\n");fail();}catch(java.io.IOException expected){}
    }
    @Test public void tokenRequestsRejectLookalikeOrRedirectedDestinations()throws Exception{
        for(String url:Arrays.asList("http://auth.openai.com/oauth/token","https://auth.openai.com.evil.test/token","https://user:pass@auth.kimi.ai/token","https://api.cline.bot:8080/token")){
            try{OAuthSubscriptions.validateUrl(url);fail(url);}catch(java.io.IOException expected){}
        }
        OAuthSubscriptions.validateUrl("https://chatgpt.com/backend-api/codex/responses");
    }
    @Test public void modelCatalogHidesUnsupportedModelsAndDeduplicates()throws Exception{
        Set<String> ids=new LinkedHashSet<>();
        OAuthSubscriptions.extractModels(new JSONObject("{\"models\":[{\"slug\":\"visible\"},{\"slug\":\"hidden\",\"visibility\":\"hide\"},{\"slug\":\"disabled\",\"supported_in_api\":false},\"visible\"]}"),ids);
        assertEquals(Collections.singleton("visible"),ids);
    }
}
