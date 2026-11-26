package app.githubble.mobile.repository;

import app.githubble.mobile.repository.RepoSecrets.Kind;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RepoSecretsTest {
    @Test public void namesFollowGitHubRules(){
        assertEquals("ANDROID_KEYSTORE_BASE64",RepoSecrets.checkName("  ANDROID_KEYSTORE_BASE64 "));
        assertEquals("_x1",RepoSecrets.checkName("_x1"));
        for(String bad:new String[]{"","1ABC","MY KEY","КЛЮЧ","GITHUB_TOKEN","github_x","a-b"}){
            try{RepoSecrets.checkName(bad);fail(bad);}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void endpointsMatchTheRestApi(){
        assertEquals("https://api.github.com/repos/o/r/actions/secrets",RepoSecrets.base("o/r",Kind.ACTIONS_SECRET));
        assertEquals("https://api.github.com/repos/o/r/actions/variables",RepoSecrets.base("o/r",Kind.ACTIONS_VARIABLE));
        assertEquals("https://api.github.com/repos/o/r/dependabot/secrets",RepoSecrets.base("o/r",Kind.DEPENDABOT_SECRET));
        try{RepoSecrets.base("../x",Kind.ACTIONS_SECRET);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void valuesAreLimited(){
        assertEquals("v",RepoSecrets.checkValue("v"));
        try{RepoSecrets.checkValue("");fail();}catch(IllegalArgumentException expected){}
        StringBuilder big=new StringBuilder();for(int i=0;i<RepoSecrets.MAX_VALUE_BYTES+1;i++)big.append('a');
        try{RepoSecrets.checkValue(big.toString());fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void listsAreParsed()throws Exception{
        List<RepoSecrets.Item> secrets=RepoSecrets.parse(new JSONObject("{\"total_count\":2,\"secrets\":[{\"name\":\"A\",\"created_at\":\"2026-01-01T00:00:00Z\",\"updated_at\":\"2026-02-02T00:00:00Z\"},{\"name\":\"\"}]}"),Kind.ACTIONS_SECRET);
        assertEquals(1,secrets.size());assertEquals("A",secrets.get(0).name);assertEquals("2026-02-02T00:00:00Z",secrets.get(0).updated);
        List<RepoSecrets.Item> vars=RepoSecrets.parse(new JSONObject("{\"variables\":[{\"name\":\"MODE\",\"value\":\"prod\"}]}"),Kind.ACTIONS_VARIABLE);
        assertEquals("prod",vars.get(0).value);
    }
}
