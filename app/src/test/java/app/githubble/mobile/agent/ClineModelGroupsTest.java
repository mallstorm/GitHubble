package app.githubble.mobile.agent;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
public class ClineModelGroupsTest {
    @Test public void freeIdsWithoutSuffixAndPassRemainSeparate()throws Exception{
        JSONObject groups=ClineModelGroups.classify(new JSONObject("{\"data\":[{\"id\":\"paid\"},{\"id\":\"rotating-free\"}]}"),
            new JSONObject("{\"free\":[\"rotating-free\"],\"clinePass\":[\"pass-model\"]}"));
        assertEquals("rotating-free",groups.getJSONArray("Free").getString(0));
        assertEquals("pass-model",groups.getJSONArray("Pass").getString(0));
        assertEquals("paid",groups.getJSONArray("Billing").getString(0));
    }
    @Test public void mapCatalogsAndMissingGenericCatalogKeepLiveFree()throws Exception{
        JSONObject groups=ClineModelGroups.classify(null,new JSONObject("{\"data\":{\"free\":{\"provider/model\":{\"name\":\"Display label\"}},\"clinePass\":[]}}"));
        assertEquals("provider/model",groups.getJSONArray("Free").getString(0));assertEquals(0,groups.getJSONArray("Billing").length());
        assertEquals(0,ClineModelGroups.classify(null,null).getJSONArray("Free").length());
    }
}
