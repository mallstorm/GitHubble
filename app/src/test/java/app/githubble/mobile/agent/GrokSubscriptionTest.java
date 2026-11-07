package app.githubble.mobile.agent;

import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import java.io.IOException;
import java.util.*;

/** Protocol fixtures, not a claim of a live xAI login or plan entitlement. */
public class GrokSubscriptionTest {
    private static final String TOKEN="https://auth.x.ai/oauth/token";
    private static final String DISCOVERY="{\"device_authorization_endpoint\":\"https://auth.x.ai/oauth/device\",\"token_endpoint\":\""+TOKEN+"\"}";
    private static final String DEVICE="{\"device_code\":\"opaque-device\",\"user_code\":\"TEST-CODE\",\"verification_uri_complete\":\"https://auth.x.ai/activate?user_code=TEST-CODE\",\"interval\":3}";
    private static final class Fixture implements OAuthSubscriptions.Transport {
        final Queue<OAuthSubscriptions.Reply> replies=new ArrayDeque<>();
        final List<String> urls=new ArrayList<>(),bodies=new ArrayList<>();
        final List<Map<String,String>> headers=new ArrayList<>();
        Fixture add(int status,String body){replies.add(new OAuthSubscriptions.Reply(status,body));return this;}
        public OAuthSubscriptions.Reply send(String url,String method,String body,boolean form,Map<String,String> h){
            urls.add(url);bodies.add(body);headers.add(h);return replies.remove();
        }
        Fixture login(){return add(200,DISCOVERY).add(200,DEVICE);}
    }
    private static JSONObject session()throws Exception{
        return new JSONObject().put("provider",OAuthSubscriptions.GROK).put("owner","account-A")
            .put("access_token","fixture-access").put("refresh_token","fixture-refresh")
            .put("token_endpoint",TOKEN).put("expires_at",System.currentTimeMillis()+3600000);
    }
    @Test public void deviceDiscoveryPendingBackoffAndSuccess()throws Exception{
        Fixture f=new Fixture().login().add(400,"{\"error\":\"authorization_pending\"}")
            .add(429,"{\"error\":\"slow_down\"}")
            .add(200,"{\"access_token\":\"access\",\"refresh_token\":\"refresh\",\"expires_in\":3600}");
        OAuthSubscriptions c=new OAuthSubscriptions(f);OAuthSubscriptions.DeviceLogin login=c.begin(OAuthSubscriptions.GROK);
        assertEquals("TEST-CODE",login.userCode);assertEquals(TOKEN,login.tokenEndpoint);
        assertTrue(f.bodies.get(1).contains("offline_access"));
        assertNull(c.poll(login));assertNull(c.poll(login));assertEquals(8000,login.interval);
        JSONObject s=c.poll(login);assertEquals(TOKEN,s.getString("token_endpoint"));
        assertEquals(OAuthSubscriptions.GROK,s.getString("provider"));
        assertTrue(f.bodies.get(2).contains("device_code=opaque-device"));
        assertFalse(f.headers.get(2).containsKey("Authorization"));
    }
    @Test public void discoveryAndBrowserRejectOtherProvidersAndLookalikes()throws Exception{
        for(String bad:Arrays.asList("https://auth.x.ai.evil.test/token","http://auth.x.ai/token",
                "https://auth.openai.com/oauth/token","https://user:pass@auth.x.ai/token","https://auth.x.ai:8080/token","https://auth.x.ai/token#fragment")){
            Fixture f=new Fixture().add(200,new JSONObject(DISCOVERY).put("token_endpoint",bad).toString());
            try{new OAuthSubscriptions(f).begin(OAuthSubscriptions.GROK);fail(bad);}catch(IOException expected){}
            assertEquals(1,f.urls.size());
        }
        Fixture f=new Fixture().add(200,DISCOVERY).add(200,new JSONObject(DEVICE).put("verification_uri_complete","https://auth.openai.com/codex/device").toString());
        try{new OAuthSubscriptions(f).begin(OAuthSubscriptions.GROK);fail();}catch(IOException expected){}
    }
    @Test public void deniedExpiredAndCancelledNeverReturnSessions()throws Exception{
        for(String error:Arrays.asList("access_denied","expired_token")){
            Fixture f=new Fixture().login().add(400,new JSONObject().put("error",error).toString());
            OAuthSubscriptions c=new OAuthSubscriptions(f);OAuthSubscriptions.DeviceLogin d=c.begin(OAuthSubscriptions.GROK);
            try{c.poll(d);fail();}catch(IOException expected){}
        }
        Fixture f=new Fixture();OAuthSubscriptions c=new OAuthSubscriptions(f);
        try{c.poll(new OAuthSubscriptions.DeviceLogin(OAuthSubscriptions.GROK,"device","code","https://auth.x.ai/activate",0,3000,TOKEN));fail();}
        catch(IOException expected){}assertTrue(f.urls.isEmpty());
        Thread.currentThread().interrupt();
        try{c.begin(OAuthSubscriptions.GROK);fail();}catch(InterruptedException expected){}finally{Thread.interrupted();}
        assertTrue(f.urls.isEmpty());
    }
    @Test public void refreshRetainsOwnerEndpointAndSupportsRotation()throws Exception{
        Fixture f=new Fixture().add(200,"{\"access_token\":\"new\",\"refresh_token\":\"rotated\"}")
            .add(200,"{\"access_token\":\"newer\"}");
        OAuthSubscriptions c=new OAuthSubscriptions(f);JSONObject fresh=c.refresh(OAuthSubscriptions.GROK,session());
        assertEquals("account-A",fresh.getString("owner"));assertEquals(TOKEN,fresh.getString("token_endpoint"));
        assertEquals("rotated",fresh.getString("refresh_token"));
        assertEquals("rotated",c.refresh(OAuthSubscriptions.GROK,fresh).getString("refresh_token"));
        assertEquals(TOKEN,f.urls.get(0));assertTrue(f.bodies.get(0).contains("grant_type=refresh_token"));
    }
    @Test public void refreshCannotSendSecretsToAnotherProvider()throws Exception{
        Fixture f=new Fixture();JSONObject old=session().put("token_endpoint","https://auth.kimi.ai/api/oauth/token");
        try{new OAuthSubscriptions(f).refresh(OAuthSubscriptions.GROK,old);fail();}catch(IOException expected){}
        assertTrue(f.urls.isEmpty());
    }
    @Test public void accessOnlySessionWorksUntilExpiryThenRequiresLogin()throws Exception{
        Fixture f=new Fixture().add(200,"{\"models\":[\"grok-live\"]}");
        JSONObject s=OAuthSubscriptions.normalize(OAuthSubscriptions.GROK,new JSONObject("{\"access_token\":\"access\"}"),null);
        OAuthSubscriptions c=new OAuthSubscriptions(f);
        assertEquals(Collections.singletonList("grok-live"),c.discover(OAuthSubscriptions.GROK,s.toString()));
        s.put("expires_at",0);
        try{c.discover(OAuthSubscriptions.GROK,s.toString());fail();}catch(IOException expected){}
        assertEquals(1,f.urls.size());
    }
    @Test public void onlyRemoteGrokModelsAndResponsesEndpointAreUsed()throws Exception{
        Fixture f=new Fixture().add(200,"{\"data\":[{\"id\":\"grok-live\"},\"grok-live\",\"unrelated\",{\"id\":\"grok-hidden\",\"visibility\":\"hide\"}]}")
            .add(200,"data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello\"}\n\n"+
                "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n");
        OAuthSubscriptions c=new OAuthSubscriptions(f);String raw=session().toString();
        assertEquals(Collections.singletonList("grok-live"),c.discover(OAuthSubscriptions.GROK,raw));
        JSONArray history=new JSONArray().put(new JSONObject().put("role","user").put("body","Hi"));
        assertEquals("Hello",new OmniChatChatClient(c).complete(OAuthSubscriptions.GROK,"grok-live",raw,history,"project","owner/repo"));
        assertEquals("https://cli-chat-proxy.grok.com/v1/responses",f.urls.get(1));
        JSONObject body=new JSONObject(f.bodies.get(1));assertTrue(body.getBoolean("stream"));assertTrue(body.has("input"));assertFalse(body.has("messages"));
        assertEquals("Bearer fixture-access",f.headers.get(1).get("Authorization"));
        assertEquals("xai-grok-cli",f.headers.get(1).get("X-XAI-Token-Auth"));
    }
    @Test public void unavailableCatalogAndPartialResponsesFailExplicitly()throws Exception{
        Fixture f=new Fixture().add(200,"{\"data\":[]}");
        try{new OAuthSubscriptions(f).discover(OAuthSubscriptions.GROK,session().toString());fail();}catch(IOException expected){}
        for(String raw:Arrays.asList("{\"status\":\"failed\",\"output_text\":\"partial\"}",
                "data: {\"type\":\"response.incomplete\"}\n\n",
                "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"incomplete\",\"output_text\":\"partial\"}}\n\n")){
            try{OAuthSubscriptions.parseResponses(raw);fail(raw);}catch(IOException expected){}
        }
    }
}
