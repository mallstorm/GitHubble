package app.githubble.mobile.agent;

import app.githubble.mobile.network.GitHubApiClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The agent's isolated cloud browser: a real Chrome driven by Playwright inside a fresh, throw-away GitHub Actions
 * runner owned by the user. Each request starts a clean virtual machine with no cookies, no accounts and no token
 * rights (contents: read only), loads the page(s), and hands text, links and search results back through the job log.
 */
public final class CloudBrowser {
    public static final String REPO = "hubble-cloud-browser", WORKFLOW = "browse.yml", SCRIPT = "browse.mjs";
    private static final String API = "https://api.github.com/repos/";

    private CloudBrowser() {}

    static final String WORKFLOW_YAML = ""
        + "name: Hubble Cloud Browser\n"
        + "run-name: browse ${{ inputs.id }}\n"
        + "on:\n"
        + "  workflow_dispatch:\n"
        + "    inputs:\n"
        + "      id:\n"
        + "        description: request id\n"
        + "        required: true\n"
        + "      request:\n"
        + "        description: JSON request\n"
        + "        required: true\n"
        + "permissions:\n"
        + "  contents: read\n"
        + "jobs:\n"
        + "  browse:\n"
        + "    runs-on: ubuntu-latest\n"
        + "    timeout-minutes: 6\n"
        + "    steps:\n"
        + "      - uses: actions/checkout@v4\n"
        + "        with:\n"
        + "          persist-credentials: false\n"
        + "      - uses: actions/setup-node@v4\n"
        + "        with:\n"
        + "          node-version: '20'\n"
        + "      - name: Install driver\n"
        + "        run: npm install --no-save --no-audit --no-fund playwright-core@1.49.1\n"
        + "      - name: Browse\n"
        + "        env:\n"
        + "          REQUEST: ${{ inputs.request }}\n"
        + "          REQUEST_ID: ${{ inputs.id }}\n"
        + "        run: node browse.mjs\n";

    static final String SCRIPT_JS = ""
        + "import { chromium } from 'playwright-core';\n"
        + "import { execSync } from 'node:child_process';\n"
        + "import fs from 'node:fs';\n"
        + "const id = process.env.REQUEST_ID;\n"
        + "const req = JSON.parse(process.env.REQUEST || '{}');\n"
        + "const MAX = Math.min(Math.max(Number(req.max_chars) || 12000, 1000), 40000);\n"
        + "const started = Date.now();\n"
        + "setTimeout(() => { emit({ ok: false, error: 'Превышено время работы браузера (150 с)' }); process.exit(0); }, 150000);\n"
        + "function emit(obj) {\n"
        + "  const b64 = Buffer.from(JSON.stringify(obj)).toString('base64');\n"
        + "  const size = 3000, total = Math.max(1, Math.ceil(b64.length / size));\n"
        + "  for (let i = 0; i < total; i++) console.log(`HUBBLE_RESULT:${id}:${i + 1}/${total}:${b64.slice(i * size, (i + 1) * size)}`);\n"
        + "}\n"
        + "const isPrivate = (h) => {\n"
        + "  h = h.toLowerCase();\n"
        + "  if (h === 'localhost' || h.endsWith('.localhost') || h.endsWith('.local') || h.endsWith('.internal') || h === 'metadata.google.internal') return true;\n"
        + "  if (/^(127\\.|10\\.|0\\.|169\\.254\\.|192\\.168\\.)/.test(h) || /^172\\.(1[6-9]|2\\d|3[01])\\./.test(h)) return true;\n"
        + "  return h === '[::1]' || h.startsWith('[fc') || h.startsWith('[fd') || h.startsWith('[fe80');\n"
        + "};\n"
        + "const okUrl = (u) => { try { const x = new URL(u); return (x.protocol === 'https:' || x.protocol === 'http:') && !isPrivate(x.hostname); } catch { return false; } };\n"
        + "async function launch() {\n"
        + "  const args = ['--no-sandbox', '--disable-dev-shm-usage'];\n"
        + "  for (const p of ['/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/usr/bin/chromium-browser']) {\n"
        + "    if (fs.existsSync(p)) { try { return await chromium.launch({ executablePath: p, args, headless: true }); } catch (e) { console.log('system chrome failed: ' + e.message); } }\n"
        + "  }\n"
        + "  execSync('npx playwright-core install --with-deps chromium', { stdio: 'inherit' });\n"
        + "  return chromium.launch({ args, headless: true });\n"
        + "}\n"
        + "async function snap(page, limit) {\n"
        + "  const title = await page.title().catch(() => '');\n"
        + "  const text = await page.evaluate(() => (document.body ? document.body.innerText : '')).catch(() => '');\n"
        + "  const links = await page.evaluate(() => [...document.querySelectorAll('a[href]')].slice(0, 120).map((a) => ({ text: (a.innerText || a.getAttribute('aria-label') || '').trim().slice(0, 100), href: a.href })).filter((l) => l.text && /^https?:/.test(l.href))).catch(() => []);\n"
        + "  const clean = text.replace(/\\n{3,}/g, '\\n\\n').trim();\n"
        + "  return { title, url: page.url(), text: clean.slice(0, limit), truncated: clean.length > limit, links: links.slice(0, 40) };\n"
        + "}\n"
        + "async function open(page, url) {\n"
        + "  if (!okUrl(url)) throw new Error('Адрес недоступен: разрешены только публичные http(s)');\n"
        + "  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });\n"
        + "  await page.waitForLoadState('networkidle', { timeout: 8000 }).catch(() => {});\n"
        + "}\n"
        + "async function search(page, query) {\n"
        + "  await open(page, 'https://www.bing.com/search?q=' + encodeURIComponent(query) + '&setlang=en');\n"
        + "  return page.evaluate(() => [...document.querySelectorAll('li.b_algo')].slice(0, 10).map((li) => {\n"
        + "    const a = li.querySelector('h2 a');\n"
        + "    const p = li.querySelector('.b_caption p, p');\n"
        + "    return a ? { title: a.innerText.trim(), url: a.href, snippet: p ? p.innerText.trim() : '' } : null;\n"
        + "  }).filter(Boolean));\n"
        + "}\n"
        + "async function runStep(page, step) {\n"
        + "  const op = step.op;\n"
        + "  if (op === 'goto') await open(page, step.url);\n"
        + "  else if (op === 'click') { if (step.selector) await page.click(step.selector, { timeout: 10000 }); else await page.getByText(step.text, { exact: false }).first().click({ timeout: 10000 }); await page.waitForLoadState('domcontentloaded', { timeout: 10000 }).catch(() => {}); }\n"
        + "  else if (op === 'fill') await page.fill(step.selector, String(step.value ?? ''), { timeout: 10000 });\n"
        + "  else if (op === 'press') await page.keyboard.press(step.key);\n"
        + "  else if (op === 'wait') { if (step.selector) await page.waitForSelector(step.selector, { timeout: 15000 }); else await page.waitForTimeout(Math.min(Number(step.ms) || 1000, 8000)); }\n"
        + "  else if (op === 'scroll') await page.evaluate((y) => window.scrollBy(0, y), Number(step.y) || 1200);\n"
        + "  else if (op === 'back') await page.goBack({ timeout: 10000 }).catch(() => {});\n"
        + "  else throw new Error('Неизвестное действие: ' + op);\n"
        + "}\n"
        + "let browser;\n"
        + "try {\n"
        + "  browser = await launch();\n"
        + "  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: req.locale || 'ru-RU', userAgent: undefined });\n"
        + "  await context.route('**/*', (route) => { let h = ''; try { h = new URL(route.request().url()).hostname; } catch { } return isPrivate(h) ? route.abort() : route.continue(); });\n"
        + "  const page = await context.newPage();\n"
        + "  const out = { ok: true, mode: req.mode };\n"
        + "  if (req.mode === 'search') {\n"
        + "    out.query = req.query;\n"
        + "    out.results = await search(page, req.query);\n"
        + "    const top = Math.min(Number(req.read_top) || 0, 3);\n"
        + "    out.pages = [];\n"
        + "    for (const r of out.results.slice(0, top)) {\n"
        + "      try { await open(page, r.url); out.pages.push(await snap(page, Math.floor(MAX / Math.max(top, 1)))); }\n"
        + "      catch (e) { out.pages.push({ url: r.url, error: String(e.message || e) }); }\n"
        + "    }\n"
        + "  } else if (req.mode === 'steps') {\n"
        + "    out.log = [];\n"
        + "    for (const step of (req.steps || []).slice(0, 25)) {\n"
        + "      try { await runStep(page, step); out.log.push({ op: step.op, ok: true }); }\n"
        + "      catch (e) { out.log.push({ op: step.op, ok: false, error: String(e.message || e).slice(0, 300) }); break; }\n"
        + "    }\n"
        + "    out.page = await snap(page, MAX);\n"
        + "  } else {\n"
        + "    await open(page, req.url);\n"
        + "    out.page = await snap(page, MAX);\n"
        + "  }\n"
        + "  out.seconds = Math.round((Date.now() - started) / 100) / 10;\n"
        + "  emit(out);\n"
        + "} catch (e) {\n"
        + "  emit({ ok: false, error: String(e.message || e).slice(0, 500) });\n"
        + "} finally {\n"
        + "  if (browser) await browser.close().catch(() => {});\n"
        + "  process.exit(0);\n"
        + "}\n";

    /** True when the browser repository already has the workflow. */
    public static boolean ready(GitHubApiClient api, String login) throws Exception {
        try {
            api.getJsonFresh(API + login + "/" + REPO + "/contents/.github/workflows/" + WORKFLOW);
            api.getJsonFresh(API + login + "/" + REPO + "/contents/" + SCRIPT);
            return true;
        } catch (GitHubApiClient.ApiException e) {
            if (e.statusCode == 404) return false;
            throw e;
        }
    }

    public static boolean repositoryExists(GitHubApiClient api, String login) throws Exception {
        try { api.getJsonFresh(API + login + "/" + REPO); return true; }
        catch (GitHubApiClient.ApiException e) { if (e.statusCode == 404) return false; throw e; }
    }

    /** Writes (or refreshes) the workflow and script into the repository; the repository must already exist. */
    public static void install(GitHubApiClient api, String login) throws Exception {
        put(api, login, ".github/workflows/" + WORKFLOW, WORKFLOW_YAML, "Cloud browser workflow");
        put(api, login, SCRIPT, SCRIPT_JS, "Cloud browser driver");
    }

    private static void put(GitHubApiClient api, String login, String path, String content, String message) throws Exception {
        String url = API + login + "/" + REPO + "/contents/" + path;
        JSONObject body = new JSONObject().put("message", message)
            .put("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        try {
            JSONObject existing = api.getJsonFresh(url);
            body.put("sha", existing.optString("sha"));
        } catch (GitHubApiClient.ApiException e) { if (e.statusCode != 404) throw e; }
        api.putJson(url, body);
    }

    public static String defaultBranch(GitHubApiClient api, String login) throws Exception {
        return api.getJsonFresh(API + login + "/" + REPO).optString("default_branch", "main");
    }

    public static void dispatch(GitHubApiClient api, String login, String ref, String id, JSONObject request) throws Exception {
        JSONObject body = new JSONObject().put("ref", ref)
            .put("inputs", new JSONObject().put("id", id).put("request", request.toString()));
        api.executeText("POST", API + login + "/" + REPO + "/actions/workflows/" + WORKFLOW + "/dispatches", body.toString(), true);
    }

    /** Finds the run started for {@code id}; 0 while GitHub has not created it yet. */
    public static long findRun(GitHubApiClient api, String login, String id) throws Exception {
        JSONObject runs = api.getJsonFresh(API + login + "/" + REPO + "/actions/workflows/" + WORKFLOW
            + "/runs?event=workflow_dispatch&per_page=10");
        JSONArray list = runs.optJSONArray("workflow_runs");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject run = list.optJSONObject(i);
            if (run != null && ("browse " + id).equals(run.optString("display_title", run.optString("name")))) return run.optLong("id");
        }
        return 0;
    }

    public static JSONObject run(GitHubApiClient api, String login, long runId) throws Exception {
        return api.getJsonFresh(API + login + "/" + REPO + "/actions/runs/" + runId);
    }

    public static long firstJob(GitHubApiClient api, String login, long runId) throws Exception {
        JSONArray jobs = api.getJsonFresh(API + login + "/" + REPO + "/actions/runs/" + runId + "/jobs").optJSONArray("jobs");
        return jobs == null || jobs.length() == 0 ? 0 : jobs.getJSONObject(0).optLong("id");
    }

    /** Re-assembles the chunks printed by browse.mjs; null when the log has no result yet. */
    public static JSONObject parseResult(String log, String id) throws Exception {
        if (log == null) return null;
        java.util.TreeMap<Integer, String> parts = new java.util.TreeMap<>();
        int total = 0;
        String marker = "HUBBLE_RESULT:" + id + ":";
        int from = 0;
        while (true) {
            int at = log.indexOf(marker, from);
            if (at < 0) break;
            int lineEnd = log.indexOf('\n', at);
            String line = log.substring(at + marker.length(), lineEnd < 0 ? log.length() : lineEnd).trim();
            from = lineEnd < 0 ? log.length() : lineEnd;
            int slash = line.indexOf('/'), colon = line.indexOf(':');
            if (slash < 0 || colon < slash) continue;
            int index = Integer.parseInt(line.substring(0, slash));
            total = Integer.parseInt(line.substring(slash + 1, colon));
            parts.put(index, line.substring(colon + 1));
        }
        if (total == 0 || parts.size() < total) return null;
        StringBuilder b64 = new StringBuilder();
        for (String part : parts.values()) b64.append(part);
        return new JSONObject(new String(Base64.getDecoder().decode(b64.toString()), StandardCharsets.UTF_8));
    }

    public static String newId() { return Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36); }
}
