package app.githubble.mobile.agent;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class QuotaProbeTest {
    @Test public void supportedProvidersIncludeTheNewOnes(){
        for(String p:new String[]{"openrouter","deepseek","kimi","siliconflow","gigachat"})assertTrue(p,QuotaProbe.supported(p));
        assertFalse(QuotaProbe.supported("openai"));
    }
    @Test public void kimiBalanceShowsCurrencyAndVouchers()throws Exception{
        JSONObject json=new JSONObject("{\"code\":0,\"data\":{\"available_balance\":49.58894,\"voucher_balance\":46.58893,\"cash_balance\":3.00001}}");
        assertEquals("Доступно $49.59 (ваучеры $46.59)",QuotaProbe.kimi(json,false));
        assertEquals("Доступно ¥49.59 (ваучеры ¥46.59)",QuotaProbe.kimi(json,true));
    }
    @Test public void siliconFlowBalanceIsRead()throws Exception{
        JSONObject json=new JSONObject("{\"code\":20000,\"data\":{\"chargeBalance\":\"88.00\",\"totalBalance\":\"88.88\"}}");
        assertEquals("Баланс: 88.88 (пополнено 88.00)",QuotaProbe.siliconFlow(json));
    }
    @Test(expected=IllegalStateException.class) public void emptyAnswersAreRejected()throws Exception{
        QuotaProbe.kimi(new JSONObject("{}"),false);
    }
}
