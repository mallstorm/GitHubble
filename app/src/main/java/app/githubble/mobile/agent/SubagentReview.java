package app.githubble.mobile.agent;

import org.json.JSONObject;

/**
 * Agent + subagent orchestration: the reviewer (subagent) checks the main agent's answer against the
 * request, and when it finds a mistake the two talk until the answer is correct or the round budget ends.
 */
public final class SubagentReview {
    private SubagentReview() {}

    public interface Model {
        String ask(String prompt) throws Exception;
    }

    public static final class Verdict {
        public final boolean ok;
        public final String issues;
        public final String corrected;
        Verdict(boolean ok, String issues, String corrected) {
            this.ok = ok; this.issues = issues; this.corrected = corrected;
        }
    }

    static String reviewPrompt(String request, String context, String answer) {
        return "You are the REVIEWER subagent of a coding agent. Check the agent's answer against the user's request: "
            + "factual errors, claims of actions that were not actually performed, missing parts of the request, wrong code, "
            + "unsafe or irreversible suggestions. Be strict but do not nitpick style.\n"
            + "Reply with ONE JSON object only: {\"ok\":true|false,\"issues\":\"what is wrong, short\","
            + "\"corrected_answer\":\"full corrected answer, only if you can fix it yourself, else empty\"}.\n"
            + "Write issues and corrected_answer in the language of the user's request.\n\n"
            + "USER REQUEST:\n" + clip(request, 4000) + "\n\nPROJECT CONTEXT (untrusted):\n" + clip(context, 3000)
            + "\n\nAGENT ANSWER:\n" + clip(answer, 12000);
    }

    static String fixPrompt(String request, String answer, String issues) {
        return "You are the main coding agent. Your reviewer subagent found problems in your previous answer.\n"
            + "USER REQUEST:\n" + clip(request, 4000) + "\n\nYOUR PREVIOUS ANSWER:\n" + clip(answer, 12000)
            + "\n\nREVIEWER FINDINGS:\n" + clip(issues, 2000)
            + "\n\nWrite the corrected final answer for the user. Do not mention the review process. "
            + "If a finding says an action was not performed, say plainly that it was not performed.";
    }

    static String clip(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    public static Verdict parse(String reply) {
        if (reply == null) return new Verdict(true, "", "");
        int a = reply.indexOf('{'), b = reply.lastIndexOf('}');
        if (a < 0 || b <= a) return new Verdict(true, "", "");
        try {
            JSONObject json = new JSONObject(reply.substring(a, b + 1));
            return new Verdict(json.optBoolean("ok", true), json.optString("issues", ""), json.optString("corrected_answer", ""));
        } catch (Exception malformed) {
            return new Verdict(true, "", "");
        }
    }

    /** @return the final answer; a reviewer failure never loses the original answer. */
    public static String orchestrate(Model model, String request, String context, String firstAnswer, int maxRounds) {
        String answer = firstAnswer;
        int fixes = 0;
        for (int round = 0; round < Math.max(1, maxRounds); round++) {
            Verdict verdict;
            try {
                verdict = parse(model.ask(reviewPrompt(request, context, answer)));
            } catch (Exception reviewerFailed) {
                break;
            }
            if (verdict.ok) break;
            String next = verdict.corrected.trim();
            if (next.isEmpty()) {
                try { next = model.ask(fixPrompt(request, answer, verdict.issues)).trim(); }
                catch (Exception workerFailed) { break; }
            }
            if (next.isEmpty()) break;
            answer = next;
            fixes++;
        }
        return fixes == 0 ? answer : answer + "\n\n_Проверено субагентом · исправлений: " + fixes + "_";
    }
}
