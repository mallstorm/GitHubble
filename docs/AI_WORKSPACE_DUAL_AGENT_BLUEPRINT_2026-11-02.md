# GitHubble AI Workspace: OmniChat + Copilot Cloud Agent
> Architecture decision / source audit, 2026-11-02. Design only: no production code changed.
> GitHubble baseline: mallstorm/GitHubble main 5e5efd911fc819d7dc990833ff66b9cd7a96e32d (RC 4.2.14).
> OmniChat source: Digger401/OmniChat main 407cc6722f52f2e360b8e4449e48d98f2989e509 (2026-10-18).

## 1. Approved product decision
- GitHubble remains the host Android application, with its package ID, signing identity, repositories, native visual style, account center and GitHub capabilities preserved.
- OmniChat's production service core is integrated into GitHubble: model providers, OAuth/API/subscription connections, conversation history, files and media, AgentEngine, workspace/project context, GitHub tools, voice/Live, memory, DNS and optionally VLESS/Xray, MCP and build tools.
- This is a code/runtime integration, **not** an embedded OmniChat window/WebView pretending to be native.
- Bottom navigation: **Главная | Репозитории | Чат | Активность | Настройки**. Global search must remain available from the top bar/other entry points. Existing GitHubble light/dark/beige native design is authoritative.
- Inside Chat: **Чат | Агент**, model/provider picker, project/repo/branch context, history, tool actions, approvals, attachments, Markdown, and task cards.
- Two first-class independent executors:
  1. **GitHubble AI Agent** (default): OmniChat AgentEngine + user-selected authorized model connection (e.g. official ChatGPT/Codex where authorized) + GitHubble GitHub tools/runtime.
  2. **GitHub Copilot Cloud Agent**: GitHub's official agent tasks and issue assignment APIs with its own plan, policies and quotas; preserve it as optional executor rather than deleting/replacing it.
- A third-party ChatGPT website WebView DOM relay is experimental and must **not** be treated as official ChatGPT OAuth or as a reliable production agent transport.

## 2. Required end-to-end scenario
The user types 'Создай приватный репозиторий MyApp и собери Android APK' from a GitHubble project chat.
1. Create the local project and bind it to the selected GitHub account, repo, branch and executor.
2. Ask for clear approval of repository creation / visibility; create repo using GitHub API under the selected account.
3. Generate architecture, documents and code, preview filesystem changes and diff, write files via Git objects/commits or a controlled git workspace.
4. Create/modify GitHub Actions workflows, queue a build, monitor jobs and retrieve logs.
5. Diagnose build errors, propose and apply controlled fixes, rerun within bounded budgets and stop criteria.
6. Show the real APK as a native GitHubble chat card (name, version, size, build SHA, account, URL, signature if known, buttons to download/install). Artifacts may expire; publish approved release builds to Releases.
7. Durable task/project ledger and resumability after process death. Never fabricate commits, builds, files, or success.

## 3. Architecture
- app-shell: Java GitHubble Activity/navigation/theme, add Kotlin/Compose capability or AndroidView bridge while preserving existing Java views.
- omnichat-core: provider catalog, auth modes, model discovery, per-connection usage, chat transport, encrypted secrets and history.
- omnichat-agent: AgentEngine, tool broker, project context, workspace/file operations, approval policy, checkpointing.
- github-bridge: account-scoped GitHubApiClient, repo management, git blobs/trees/commit/PR/review, workflow dispatch, log/artifact/release delivery.
- copilot-cloud-adapter: GitHub Agent tasks API + issue assignment + custom agents + official session links/PR review (not an internal LLM substitute).
- optional capabilities: live/voice, memory, MCP, file preview, DNS, Xray. Migrate in isolated modules and keep dependencies optional where possible.
- Prefer standard public APIs and supported OAuth; do not reuse website cookies as API credentials.
- Separate provider tokens from GitHub tokens. A running task snapshots account ID and repo; account switching must not redirect ongoing writes to a new account.
- On migration, Android app Keystore credentials for OmniChat cannot simply be copied to GitHubble package. Expect reauthorization or a sanctioned in-app transfer.
- Approval required for repository creation, branch protection changes, pushes to protected branch, PR merge, workflow modifications, release publishing and destructive operations. Non-destructive reads can be automatic.
- Readable activity trail: action, source executor/model, target account/repo, approval, GitHub API response, build outcome.

## 4. Current official Copilot integration (verified source)
**Present**:
- `controller/CopilotAgentController.java`: repository/user task list, get task, start task with prompt/model/custom agent/base/head/PR flag; issue assignment; suggestedActors availability; custom-agent listing; read-only permission matrix; task/PR artifact helpers.
- `MainActivity.java` ~2738-2949: repo Copilot page, task creation dialog/templates, model selection, custom-agent picker, permission screen, task listing, detail/sessions/artifacts, open PR/GitHub links, issue assignment.
- `copilot/CopilotTaskMonitorJobService.java`: periodic/background status polling and notifications.
- `copilot/CopilotNotificationHelper.java`: Android notifications on task state changes.
- `network/GitHubApiClient.java`: GitHub API version 2026-03-10 and GitHub App user token.

**Gaps and implementation risks**:
1. **No native interactive session steering/chat**. Detail screen shows session metadata and links to GitHub; no streaming session transcript or user follow-up within GitHubble. Current public Agent Tasks REST API documents list/create/get, **not** a general steer/stop/archive mutation. Do NOT invent endpoints; use official GitHub session webpage when no supported API exists.
2. **No native stop/archive control**. `MainActivity.showCopilotArchiveInfo` explicitly opens GitHub, and `CopilotAgentController.archiveMutationAvailable()` returns false. Stop may be available in official GitHub UI and possibly via supported Actions operations for specific runs, but never equate cancelling an unrelated workflow with a session stop.
3. **No native session log renderer**. Only “Открыть журнал сеанса” hyperlink; use public supported log endpoints only, otherwise link to GitHub. Do not promise access to hidden reasoning.
4. **Static model catalog**: `copilotModelLabels` ~2936 is hardcoded. Github allowed models depend on plan/policy and may change. Validate remote rejection, prefer automatic selection fallback, show policy/plan-dependent limits.
5. **Limited custom-agent management**: list profiles in `.github/agents`, selection works; create profile opens GitHub; no native edit/versioning, org agents or shared instruction/skills/MCP management.
6. **Polling is shallow**: background job only fetches first 30 unarchived user tasks, replaces previous states with that page, globally stores `copilot_monitor_states` and counters in generic preferences. Needs per-account durable state, pagination/since, synchronization, process-death handling and deduped notification transitions. Foreground task screen polls by full screen refresh.
7. **Permission report is incomplete by design**: read-only probes verify repo/tasks/contents/Issues/PR/Actions reads and GraphQL assignability; do not prove write permission or active Copilot plan. UI must distinguish confirmed, unknown and denied. Needed GitHub App permission: Agent tasks repository read/write plus Issue/Contents/Pull requests/Actions/etc for delegated workflows.
8. **No complete Copilot PR→build→APK card orchestration**: task artifact list supports generic PR open, but no dedicated linkage of resulting PR head SHA to workflow run, build logs, final artifact and chat download flow.
9. **UI remains legacy** within `MainActivity.shell`, `heroBanner`, `card` and inline Copilot pages; migrate to the same native GitHubble design system as the integrated chat.
10. **Source-level audit only**: working UI/API entitlement, live test with the user's GitHub token, license and organization policies, emulator/device and signed APK were not tested in this audit.

## 5. GitHub platform reality (checked 2026-11-02)
- Public-preview Agent Tasks REST API exposes list repo/user tasks, start, and read individual tasks. GitHub App **user-to-server** tokens supported; installation tokens not supported. See https://docs.github.com/en/rest/agent-tasks/agent-tasks?apiVersion=2026-03-10
- StartTask supports `prompt`, `model`, `custom_agent`, `create_pull_request`, `base_ref`, `head_ref`. Issue assignments use official Issue APIs. See https://docs.github.com/en/copilot/how-tos/use-copilot-agents/cloud-agent/use-cloud-agent-via-the-api
- June 4, 2026 changelog expanded Agent Tasks API to Copilot Pro, Pro+ and Max beyond initial Business/Enterprise; documentation of some individual endpoints still says Business/Enterprise. Respect actual live entitlement and do not assume based on stale prose. https://github.blog/changelog/2026-06-04-agent-tasks-rest-api-now-available-for-copilot-pro-pro-and-max/
- GitHub UI supports steering, stopping, archiving, and session logs; availability of a feature in GitHub's own UI **does not imply a public REST endpoint**. https://docs.github.com/en/copilot/how-tos/copilot-on-github/use-copilot-agents/manage-and-track-agents
- Copilot environment setup, custom agents, MCP and instructions can be implemented as repository configuration where documented; `.github/workflows/copilot-setup-steps.yml` is relevant to Copilot sessions running on Actions, not every cloud sandbox session. https://docs.github.com/en/copilot/how-tos/copilot-on-github/customize-copilot/customize-cloud-agent/customize-the-agent-environment

## 6. Implementation passes and acceptance criteria
**Pass A — foundations**:
- Gradle Kotlin+Compose compatibility with GitHubble Java/AGP, isolated service modules; unified GitHub account-context bridge and encrypted provider secret storage; create repository/project API with approvals and typed result.
- Native Chat tab, model/provider/agent picker and project context; preserve search and current navigation. GitHubble RC regression tests pass.

**Pass B — OmniChat service migration**:
- Fully migrate provider connections, chat, memory, multimodal attachments, agent tools, workspace, voice/Live, security/network extensions via independently testable modules.
- Every provider in the Digger401 source is either connected and verified, or labelled unsupported/experimental, never silently omitted or asserted working without verification.
- Stable tool and task state after switching screen/closing app; no credentials exposed to agent text.

**Pass C — Copilot 2.0 parity + release**:
- Keep official Cloud Agent API compatible with active plan/permissions, task listing/paging, Issue assignment, custom agent selection and PR review.
- Build a native mission-control UI; add supported session controls through documented APIs, fallback to official GitHub session screen where unavailable.
- Integrate PR diff/reviews/checks, Actions monitoring, diagnostics, artifact download and optional approved Release publishing.
- Real end-to-end tests: fresh private and public repos, multiple accounts, permission denial, Copilot plan denial, new project → code → commit → PR → successful Actions APK download, restoration after app restart.
- Release signed under the existing GitHubble signing identity; no claims of completed implementation until successful tested build.

## 7. Critical principle
Neither ChatGPT nor Copilot **is** the GitHub account. The selected GitHub user grants the Android tool broker scoped rights. All writes must be authorized, deterministic, attributable and verifiable. GitHubble orchestrates; GitHub provides source/Actions; OmniChat provides AI connections; Copilot remains an optional official cloud executor.
