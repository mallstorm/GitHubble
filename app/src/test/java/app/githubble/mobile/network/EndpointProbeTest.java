package app.githubble.mobile.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.Test;

public class EndpointProbeTest {
    private static final String OPENAI_LIST = "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-4o\"},{\"id\":\"gpt-4o-mini\"}]}";
    private static final String ANTHROPIC_LIST = "{\"data\":[{\"type\":\"model\",\"id\":\"claude-sonnet\",\"display_name\":\"Claude Sonnet\"}],\"has_more\":false}";

    @Test public void normalizeCleansTypedValues() {
        assertEquals("https://keydealer.ru", EndpointProbe.normalize("keydealer.ru"));
        assertEquals("https://x.com/v1", EndpointProbe.normalize(" https://X.com/v1/chat/completions/ "));
        assertEquals("https://x.com/api/v1", EndpointProbe.normalize("https://x.com/api/v1/models"));
        assertEquals("http://localhost:11434", EndpointProbe.normalize("localhost:11434"));
        assertEquals("", EndpointProbe.normalize("http://example.com/v1"));
        assertEquals("", EndpointProbe.normalize("https://x.com/v1?key=1"));
        assertEquals("", EndpointProbe.normalize("https://user:pw@x.com"));
        assertEquals("", EndpointProbe.normalize("ftp://x.com"));
        assertEquals("", EndpointProbe.normalize("not a url"));
        assertEquals("", EndpointProbe.normalize(""));
    }

    @Test public void typedAddressNeverLeavesItsHost() {
        for (EndpointDirectory.Candidate c : EndpointProbe.candidates("custom-claudy", "https://claudy.shop")) {
            assertEquals("claudy.shop", URI.create(c.base).getHost());
        }
        List<EndpointDirectory.Candidate> proxied = EndpointProbe.candidates("openrouter", "https://my-proxy.example/v1");
        for (EndpointDirectory.Candidate c : proxied) assertEquals("my-proxy.example", URI.create(c.base).getHost());
    }

    @Test public void knownServiceUsesItsDirectoryWhenNothingTyped() {
        List<EndpointDirectory.Candidate> list = EndpointProbe.candidates("openai", "");
        assertEquals("https://api.openai.com/v1", list.get(0).base);
        assertTrue(EndpointProbe.candidates("azure", "").isEmpty());
        assertEquals("https://api.groq.com/openai/v1", EndpointDirectory.base("groq"));
        assertTrue(EndpointDirectory.personal("azure"));
        assertFalse(EndpointDirectory.personal("deepinfra"));
    }

    @Test public void typedKnownHostAddsAlternatives() {
        List<EndpointDirectory.Candidate> list = EndpointProbe.candidates("kimi", "https://api.moonshot.cn");
        boolean hasChina = false;
        for (EndpointDirectory.Candidate c : list) if (c.base.equals("https://api.moonshot.cn/v1")) hasChina = true;
        assertTrue(hasChina);
    }

    @Test public void findsEveryWorkingEndpointAndPicksOpenAiFirst() throws Exception {
        final Set<String> hosts = ConcurrentHashMap.newKeySet();
        EndpointProbe.Fetcher fetcher = (method, url, headers, body) -> {
            hosts.add(URI.create(url).getHost());
            if (url.equals("https://claudy.shop/v1/models") && headers.containsKey("Authorization")) return new EndpointProbe.Reply(200, OPENAI_LIST);
            if (url.equals("https://claudy.shop/anthropic/v1/models") && headers.containsKey("x-api-key")) return new EndpointProbe.Reply(200, ANTHROPIC_LIST);
            if (url.equals("https://claudy.shop/models")) return new EndpointProbe.Reply(200, "<html>site</html>");
            return new EndpointProbe.Reply(404, "{\"error\":\"not found\"}");
        };
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            EndpointProbe.Report report = EndpointProbe.run("custom-claudy", "claudy.shop", "sk-test", fetcher, pool, 10000);
            assertEquals(2, report.working().size());
            EndpointProbe.Hit best = report.best();
            assertNotNull(best);
            assertEquals("https://claudy.shop/v1", best.candidate.base);
            assertEquals("openai-chat", best.candidate.protocol);
            assertEquals(2, best.models.size());
            assertEquals("anthropic", report.working().get(1).candidate.protocol);
            assertEquals("https://claudy.shop/anthropic/v1", report.working().get(1).candidate.base);
            assertEquals(1, hosts.size());
            assertTrue(hosts.contains("claudy.shop"));
        } finally { pool.shutdownNow(); }
    }

    @Test public void rejectedKeyIsReportedWithoutPickingAnEndpoint() throws Exception {
        EndpointProbe.Fetcher fetcher = (method, url, headers, body) -> url.equals("https://keydealer.ru/v1/models")
                ? new EndpointProbe.Reply(401, "{\"error\":{\"message\":\"bad key\"}}") : new EndpointProbe.Reply(404, "");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            EndpointProbe.Report report = EndpointProbe.run("custom-keydealer", "https://keydealer.ru", "wrong", fetcher, pool, 10000);
            assertNull(report.best());
            assertEquals(1, report.rejected().size());
            assertEquals("https://keydealer.ru/v1", report.rejected().get(0).candidate.base);
        } finally { pool.shutdownNow(); }
    }

    @Test public void htmlAndErrorsAreNotEndpoints() {
        EndpointDirectory.Candidate c = new EndpointDirectory.Candidate("https://x.com/v1", "openai-chat");
        assertEquals(EndpointProbe.Kind.MISS, EndpointProbe.evaluate(c, new EndpointProbe.Reply(200, "<html></html>"), 0).kind);
        assertEquals(EndpointProbe.Kind.MISS, EndpointProbe.evaluate(c, new EndpointProbe.Reply(401, "<html>login</html>"), 0).kind);
        assertEquals(EndpointProbe.Kind.MISS, EndpointProbe.evaluate(c, new EndpointProbe.Reply(500, "{}"), 0).kind);
        assertEquals(EndpointProbe.Kind.RATE, EndpointProbe.evaluate(c, new EndpointProbe.Reply(429, "{\"error\":1}"), 0).kind);
        assertEquals(EndpointProbe.Kind.OK, EndpointProbe.evaluate(c, new EndpointProbe.Reply(200, OPENAI_LIST), 0).kind);
    }

    @Test public void wireShapesAreChecked() {
        assertNull(EndpointProbe.modelIds(OPENAI_LIST, "anthropic"));
        assertEquals(1, EndpointProbe.modelIds(ANTHROPIC_LIST, "anthropic").size());
        List<String> gemini = EndpointProbe.modelIds("{\"models\":[{\"name\":\"models/gemini-2.5-pro\"}]}", "gemini");
        assertEquals("gemini-2.5-pro", gemini.get(0));
        assertEquals(1, EndpointProbe.modelIds("{\"models\":[{\"name\":\"llama3\"}]}", "ollama").size());
        assertNull(EndpointProbe.modelIds("{\"data\":{}}", "openai-chat"));
    }

    @Test public void headersMatchTheWire() {
        Map<String, String> openai = EndpointProbe.headers(new EndpointDirectory.Candidate("https://x/v1", "openai-chat"), "k");
        assertEquals("Bearer k", openai.get("Authorization"));
        Map<String, String> anthropic = EndpointProbe.headers(new EndpointDirectory.Candidate("https://x/v1", "anthropic"), "k");
        assertEquals("k", anthropic.get("x-api-key"));
        assertFalse(anthropic.containsKey("Authorization"));
        assertTrue(EndpointProbe.headers(new EndpointDirectory.Candidate("http://127.0.0.1:11434", "ollama"), "").get("Accept") != null);
        assertFalse(EndpointProbe.headers(new EndpointDirectory.Candidate("https://x/v1", "openai-chat"), "").containsKey("Authorization"));
    }

    @Test public void dashboardAndHealthPathsMeanTheHost() {
        assertEquals("https://keydealer.ru", EndpointProbe.normalize("https://keydealer.ru/check"));
        assertEquals("https://keydealer.ru", EndpointProbe.normalize("https://keydealer.ru/dashboard/keys"));
        assertEquals("https://keydealer.ru/v1", EndpointProbe.normalize("https://keydealer.ru/v1"));
    }

    @Test public void recognisesGatewaySoftware() {
        assertEquals("LiteLLM", EndpointProbe.gatewayOf(new EndpointProbe.Reply(200, "{}", "uvicorn litellm", 5)));
        assertEquals("New API", EndpointProbe.gatewayOf(new EndpointProbe.Reply(401, "{\"error\":\"new-api: invalid token\"}", "", 5)));
        assertEquals("", EndpointProbe.gatewayOf(new EndpointProbe.Reply(200, "{}", "nginx", 5)));
    }

    @Test public void failureReasonIsReadable() throws Exception {
        EndpointProbe.Fetcher dead = (method, url, headers, body) -> { throw new java.net.UnknownHostException("nope.invalid"); };
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            EndpointProbe.Report report = EndpointProbe.run("custom-x", "https://nope.invalid", "k", dead, pool, 5000);
            assertNull(report.best());
            assertEquals("Хост не найден (DNS)", report.failure);
        } finally { pool.shutdownNow(); }
        assertEquals("Сервер не отвечает (таймаут)", EndpointProbe.describeFailure(new java.net.SocketTimeoutException("timeout")));
        assertEquals("Соединение отклонено", EndpointProbe.describeFailure(new java.net.ConnectException("Connection refused")));
    }

    @Test public void pingExplainsRefusalsAndNeverLeaksTheKey() {
        EndpointDirectory.Candidate c = new EndpointDirectory.Candidate("https://x.com/v1", "openai-chat");
        final String[] seenUrl = {""};
        final String[] seenBody = {""};
        EndpointProbe.Ping ok = EndpointProbe.ping(c, "sk-secret", "gpt-mini", (m, url, h, body) -> {
            seenUrl[0] = url; seenBody[0] = body;
            assertEquals("POST", m);
            return new EndpointProbe.Reply(200, "{}", "", 120);
        });
        assertTrue(ok.ok);
        assertEquals("https://x.com/v1/chat/completions", seenUrl[0]);
        assertTrue(seenBody[0].contains("\"max_tokens\":1"));
        assertFalse(seenBody[0].contains("sk-secret"));
        assertEquals("Нет средств или токенов на балансе", EndpointProbe.ping(c, "k", "m", (m, u, h, b) -> new EndpointProbe.Reply(402, "{}")).message);
        assertEquals("Лимит запросов или квота исчерпаны", EndpointProbe.ping(c, "k", "m", (m, u, h, b) -> new EndpointProbe.Reply(429, "{}")).message);
        EndpointProbe.Ping dead = EndpointProbe.ping(c, "k", "m", (m, u, h, b) -> { throw new java.net.SocketTimeoutException("t"); });
        assertFalse(dead.ok);
        EndpointDirectory.Candidate anthropic = new EndpointDirectory.Candidate("https://x.com/v1", "anthropic");
        EndpointProbe.ping(anthropic, "k", "claude", (m, url, h, b) -> {
            assertEquals("https://x.com/v1/messages", url);
            assertEquals("k", h.get("x-api-key"));
            return new EndpointProbe.Reply(200, "{}");
        });
    }

    @Test public void pingModelPrefersSmallChatModels() {
        assertEquals("gpt-4o-mini", EndpointProbe.pickPingModel(java.util.Arrays.asList("text-embedding-3", "gpt-4o", "gpt-4o-mini")));
        assertEquals("gemini-2.5-flash", EndpointProbe.pickPingModel(java.util.Arrays.asList("gemini-2.5-pro", "gemini-2.5-flash")));
        assertEquals("some-model", EndpointProbe.pickPingModel(java.util.Arrays.asList("some-model", "other")));
        assertEquals("", EndpointProbe.pickPingModel(java.util.Collections.<String>emptyList()));
    }
}
