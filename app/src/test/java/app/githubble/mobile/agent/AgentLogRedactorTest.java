package app.githubble.mobile.agent;

import static org.junit.Assert.*;
import org.junit.Test;

public final class AgentLogRedactorTest {
    @Test public void hidesCommonGithubAndApiCredentials() {
        String source = "error: authorization: Bearer secretValue123\n"
                + "failed api_key=abcde0123456789\n"
                + "Exception: ghp_12345678901234567890\n"
                + "error: password=leaked_value";
        String result = AgentLogRedactor.extract(source);
        assertFalse(result.contains("secretValue123"));
        assertFalse(result.contains("abcde0123456789"));
        assertFalse(result.contains("ghp_12345678901234567890"));
        assertFalse(result.contains("leaked_value"));
        assertTrue(result.contains("[REDACTED]"));
    }

    @Test public void includesBuildErrorContextButNotUnrelatedLines() {
        String result = AgentLogRedactor.extract("ordinary-1\nordinary-2\nordinary-3\n"
                + "error: cannot find symbol\nsymbol: class MissingType\nordinary-6\nordinary-7\nordinary-8\nordinary-9");
        assertTrue(result.contains("cannot find symbol"));
        assertTrue(result.contains("MissingType"));
        assertFalse(result.contains("ordinary-1"));
        assertFalse(result.contains("ordinary-9"));
    }

    @Test public void capsDiagnosticContext() {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 5000; i++) raw.append("error: failure ").append(i).append('\n');
        assertTrue(AgentLogRedactor.extract(raw.toString()).length() <= 11040);
    }

    @Test public void skipsUnrelatedLog() {
        assertTrue(AgentLogRedactor.extract("nothing unusual\nstatus ok").contains("не удалось"));
    }
}
