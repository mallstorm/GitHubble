package app.githubble.mobile.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Known API endpoints per catalog service. The first entry is the default that is pre-filled when a
 * service is chosen; the rest are alternatives (regions, old and new paths) that the endpoint check
 * also tries. Services with no public address (a personal Azure resource, a Databricks workspace)
 * only carry a hint for the input field.
 */
public final class EndpointDirectory {
    private EndpointDirectory() {}

    public static final String CHAT = "openai-chat", ANTHROPIC = "anthropic", GEMINI = "gemini", OLLAMA = "ollama";

    public static final class Candidate {
        public final String base, protocol;
        public Candidate(String base, String protocol) { this.base = base; this.protocol = protocol; }
        @Override public boolean equals(Object o) {
            return o instanceof Candidate && ((Candidate) o).base.equals(base) && ((Candidate) o).protocol.equals(protocol);
        }
        @Override public int hashCode() { return base.hashCode() * 31 + protocol.hashCode(); }
        @Override public String toString() { return base + " [" + protocol + "]"; }
    }

    private static final Map<String, List<Candidate>> BASES = new LinkedHashMap<>();
    private static final Map<String, String> HINTS = new LinkedHashMap<>();

    /** Adds "url" or "url|protocol" entries; no protocol means the OpenAI chat wire. */
    private static void put(String id, String... entries) {
        List<Candidate> list = new ArrayList<>();
        for (String entry : entries) {
            int cut = entry.indexOf('|');
            list.add(cut < 0 ? new Candidate(entry, CHAT) : new Candidate(entry.substring(0, cut), entry.substring(cut + 1)));
        }
        BASES.put(id, Collections.unmodifiableList(list));
    }

    private static void hint(String id, String placeholder) { HINTS.put(id, placeholder); }

    static {
        // ---- direct vendors
        put("openai", "https://api.openai.com/v1|openai-responses", "https://api.openai.com/v1");
        put("anthropic", "https://api.anthropic.com/v1|anthropic");
        put("gemini", "https://generativelanguage.googleapis.com/v1beta|gemini", "https://generativelanguage.googleapis.com/v1beta/openai");
        put("deepseek", "https://api.deepseek.com/v1", "https://api.deepseek.com", "https://api.deepseek.com/anthropic|anthropic");
        put("xai", "https://api.x.ai/v1");
        put("mistral", "https://api.mistral.ai/v1");
        put("perplexity", "https://api.perplexity.ai");
        put("cohere", "https://api.cohere.ai/compatibility/v1", "https://api.cohere.com/compatibility/v1");
        put("ai21", "https://api.ai21.com/studio/v1");
        put("alibaba", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", "https://dashscope.aliyuncs.com/compatible-mode/v1");
        put("kimi", "https://api.moonshot.ai/v1", "https://api.moonshot.cn/v1");
        put("kimi-code", "https://api.kimi.ai/coding/v1", "https://api.kimi.com/coding/v1");
        put("minimax", "https://api.minimax.io/v1", "https://api.minimaxi.com/v1", "https://api.minimax.io/anthropic|anthropic");
        put("zai", "https://api.z.ai/api/paas/v4", "https://open.bigmodel.cn/api/paas/v4", "https://api.z.ai/api/anthropic|anthropic");
        put("stepfun", "https://api.stepfun.com/v1", "https://api.stepfun.ai/v1");
        put("baichuan", "https://api.baichuan-ai.com/v1");
        put("upstage", "https://api.upstage.ai/v1");
        put("hunyuan", "https://api.hunyuan.cloud.tencent.com/v1");
        put("qianfan", "https://qianfan.baidubce.com/v2");
        put("volcengine", "https://ark.cn-beijing.volces.com/api/v3");
        put("byteplus", "https://ark.ap-southeast.bytepluses.com/api/v3");
        put("gigachat", "https://gigachat.devices.sberbank.ru/api/v1", "https://api.giga.chat/v1");
        put("yandex", "https://ai.api.cloud.yandex.net/v1", "https://llm.api.cloud.yandex.net/v1");
        put("cloudru", "https://foundation-models.api.cloud.ru/v1");
        put("modelscope", "https://api-inference.modelscope.cn/v1");
        put("infini", "https://cloud.infini-ai.com/maas/v1");

        // ---- gateways, aggregators and inference clouds
        put("openrouter", "https://openrouter.ai/api/v1");
        put("nanogpt", "https://nano-gpt.com/api/v1");
        put("groq", "https://api.groq.com/openai/v1");
        put("together", "https://api.together.ai/v1", "https://api.together.xyz/v1");
        put("fireworks", "https://api.fireworks.ai/inference/v1");
        put("cerebras", "https://api.cerebras.ai/v1");
        put("sambanova", "https://api.sambanova.ai/v1");
        put("siliconflow", "https://api.siliconflow.com/v1", "https://api.siliconflow.cn/v1");
        put("nvidia", "https://integrate.api.nvidia.com/v1");
        put("huggingface", "https://router.huggingface.co/v1");
        put("replicate", "https://api.replicate.com/v1");
        put("deepinfra", "https://api.deepinfra.com/v1/openai");
        put("hyperbolic", "https://api.hyperbolic.xyz/v1");
        put("novita", "https://api.novita.ai/openai/v1", "https://api.novita.ai/v3/openai");
        put("friendli", "https://api.friendli.ai/serverless/v1");
        put("featherless", "https://api.featherless.ai/v1");
        put("chutes", "https://llm.chutes.ai/v1");
        put("venice", "https://api.venice.ai/api/v1");
        put("kluster", "https://api.kluster.ai/v1");
        put("nebius", "https://api.studio.nebius.com/v1", "https://api.tokenfactory.nebius.com/v1");
        put("scaleway", "https://api.scaleway.ai/v1");
        put("ovhcloud", "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1");
        put("baseten", "https://inference.baseten.co/v1");
        put("lambda", "https://api.lambda.ai/v1", "https://api.lambdalabs.com/v1");
        put("nscale", "https://inference.api.nscale.com/v1");
        put("ppio", "https://api.ppinfra.com/v3/openai", "https://api.ppinfra.com/openai/v1");
        put("aihubmix", "https://aihubmix.com/v1");
        put("302ai", "https://api.302.ai/v1");
        put("requesty", "https://router.requesty.ai/v1");
        put("portkey", "https://api.portkey.ai/v1");
        put("helicone", "https://ai-gateway.helicone.ai/v1", "https://oai.helicone.ai/v1");
        put("digitalocean-genai", "https://inference.do-ai.run/v1");
        put("gcore", "https://api.gcore.com/llm/v1");
        put("opencode-zen", "https://opencode.ai/zen/v1");
        put("opencode-go", "https://opencode.ai/zen/go/v1");
        put("zoo-gateway", "https://www.zoocode.dev/api/gateway/v1");
        put("polza", "https://polza.ai/api/v1");
        put("vsegpt", "https://api.vsegpt.ru/v1");
        put("bedrock", "https://bedrock-runtime.us-east-1.amazonaws.com/openai/v1");

        // ---- local servers (default ports)
        put("ollama", "http://127.0.0.1:11434|ollama", "http://127.0.0.1:11434/v1");
        put("lmstudio", "http://127.0.0.1:1234/v1");
        put("vllm", "http://127.0.0.1:8000/v1");
        put("localai", "http://127.0.0.1:8080/v1");
        put("litellm", "http://127.0.0.1:4000/v1");
        put("oneapi", "http://127.0.0.1:3000/v1");
        put("newapi", "http://127.0.0.1:3000/v1");
        put("openwebui", "http://127.0.0.1:3000/api");
        put("llamacpp", "http://127.0.0.1:8080/v1");
        put("sglang", "http://127.0.0.1:30000/v1");
        put("aphrodite", "http://127.0.0.1:2242/v1");
        put("xinference", "http://127.0.0.1:9997/v1");
        put("tgi", "http://127.0.0.1:8080/v1");
        put("jan", "http://127.0.0.1:1337/v1");
        put("msty", "http://127.0.0.1:10000/v1");
        put("koboldcpp", "http://127.0.0.1:5001/v1");
        put("tabbyapi", "http://127.0.0.1:5000/v1");
        put("textgenwebui", "http://127.0.0.1:5000/v1");
        put("openllm", "http://127.0.0.1:3000/v1");
        put("ray-serve", "http://127.0.0.1:8000/v1");
        put("triton-openai", "http://127.0.0.1:9000/v1");
        put("proxy-external", "http://127.0.0.1:8787/v1");
        put("github-models", "https://models.github.ai/inference");

        // ---- personal addresses: only a hint, the user supplies the real one
        hint("azure", "https://<ресурс>.openai.azure.com/openai/v1");
        hint("databricks", "https://<workspace>.cloud.databricks.com/serving-endpoints");
        hint("watsonx", "https://<регион>.ml.cloud.ibm.com/ml/v1");
        hint("modal", "https://<приложение>--serve.modal.run/v1");
        hint("lepton", "https://<модель>.lepton.run/api/v1");
        hint("runpod", "https://api.runpod.ai/v2/<id>/openai/v1");
        hint("predibase", "https://serving.app.predibase.com/<tenant>/deployments/v2/llms/<модель>/v1");
        hint("oracle-genai", "https://inference.generativeai.<регион>.oci.oraclecloud.com/openai/v1");
        hint("akasha", "https://<ваш-хост>/v1");
        hint("anyscale-compatible", "https://<ваш-хост>/v1");
        hint("cloudflare", "Account ID вместо адреса");
        hint("custom", "https://api.example.com/v1");
    }

    /** All known endpoints of a service, default first; empty for personal-address services. */
    public static List<Candidate> candidates(String provider) {
        List<Candidate> list = BASES.get(provider);
        return list == null ? Collections.<Candidate>emptyList() : list;
    }

    /** Default base URL or "" when the address is personal. */
    public static String base(String provider) {
        List<Candidate> list = candidates(provider);
        return list.isEmpty() ? "" : list.get(0).base;
    }

    /** Default wire protocol of the first endpoint, or "" when unknown. */
    public static String protocol(String provider) {
        List<Candidate> list = candidates(provider);
        return list.isEmpty() ? "" : list.get(0).protocol;
    }

    /** Placeholder for the address field when the service has no public endpoint. */
    public static String hint(String provider) {
        String value = HINTS.get(provider);
        return value == null ? "https://api.example.com/v1" : value;
    }

    public static boolean personal(String provider) { return candidates(provider).isEmpty(); }
}
