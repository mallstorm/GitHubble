# OmniChat → GitHubble: implementation roadmap

Baseline: GitHubble RC 4.2.14 (mallstorm/GitHubble main, 5e5efd911fc8).
Source of truth for OmniChat: Digger401/OmniChat main, 407cc6722f52 (2026-10-18).
Architecture decision: [AI_WORKSPACE_DUAL_AGENT_BLUEPRINT_2026-11-02.md](AI_WORKSPACE_DUAL_AGENT_BLUEPRINT_2026-11-02.md).
Owner app ID and signing remain GitHubble's.

## Stage 1 — native foundation and GitHub tool bridge (this PR)
- Keep current GitHubble Java UI/navigation and all existing GitHub features; replace bottom tab Search with Chat, retain GitHub search as a top action and Home quick action.
- Native chat UI conforming to existing GitHubble colors, account identity, project, conversation, executor switch.
- Project/chat ledger scoped by account (first-pass SharedPreferences; no credentials; limited length). Persist selected project and branch; do not leak project across accounts.
- GitHub tool bridge with active-account check: create private/public repository, attach existing repo, query available workflows and dispatch, launch existing GitHub Copilot cloud tasks. Every mutation receives explicit approval.
- Import provider metadata **without claiming auth/transport works** from OmniChat; no fake model output.
- Copilot remains accessible independently in existing screens and as an executor in Chat.
- Verify native Android CI and draft PR; never publish unreviewed code directly to main.
**Acceptance**: native Chat tab navigable, current GitHub search remains accessible, local projects persist per account, repository creation and workflow dispatch use GitHub API rather than fake messages, existing features compile. Live API use requires an authorized account and is not established by CI compilation.

## Stage 2 — extract OmniChat connection/transport engine
- Change OmniChat's monolith into buildable Android library modules (provider/auth/network/security/core) or import its tested Kotlin parts as a separate module.
- Resolve GitHubble AGP/Java vs OmniChat Kotlin/Compose compatibility deliberately, preserving GitHubble release-signing and applicationId.
- Implement ProviderConnectionRepository + encrypted token vault + OAuth callback broker; support API keys, device flow and officially supported subscription OAuth. Keep Android WebView experiments labelled experimental and isolated.
- Bridge actual model catalog, live availability/limits and per-connection model selection.
- Add provider-specific integration tests and full error diagnostics: OpenAI/Codex, Cline, Gemini, Grok, Kimi, Requesty, NVIDIA and other available entries.
**Acceptance**: real authenticated messages from providers, no token reuse across accounts or providers, correct error/limit handling.

## Stage 3 — complete AI chat UX, state and memory
- Build unified project/conversation/message schema in Room; migrate Phase-1 SharedPreferences; preserve chat history.
- Add streaming, Markdown, code, attachments, file previews, model-switch behavior, multi-conversation memory and project checkpoints.
- Native GitHubble visual styling of chat/tool status/approvals, dark/light/beige, accessibility and compact density.
**Acceptance**: repeated messages/history after process death and account switch, real streaming, no accidental chat reset.

## Stage 4 — full GitHubble AI Agent with OmniChat engine
- Import AgentEngine and tool orchestration; expose typed GitHub tools for repo create, clone/inspect, files, trees, commits, branches, PR, checks, workflows, logs, releases, artifacts.
- Immutable account/repository binding per task and preferably frozen auth token/actor-bound network client; compare account on response. Never execute write via mutable global token after switching accounts.
- Durable queue, explicit approvals, transaction-level audit, bounded retries, interruption and resume.
- Project templating: Android Gradle, source files, docs, GitHub Actions; staged file batches with diff and tests.
**Acceptance**: single user message creates approved repo, commits a compilable project, starts Actions; task survives app restart.

## Stage 5 — official GitHub Copilot Cloud Agent 2.0
- Preserve the official task API, issue assignment, models and custom agents, unify project scope and timeline with AI Agent.
- Expand status handling, pagination, per-account background monitoring, clear plan/permissions diagnostic matrix.
- Native PR results, diff, CI linkage, generated commits and session metadata. Support user-session controls only when GitHub exposes documented API; otherwise route to official GitHub page.
- Copilot remains optional and billable by GitHub independently of OmniChat/ChatGPT.
**Acceptance**: native repo task lifecycle, trustworthy status, no pretend session steering/stop/archive.

## Stage 6 — OmniChat extended features
- Voice (STT/TTS), Live/conference, attachments/media, visual-generation routing, provider quotas.
- Workspace, filesystem/LSP, MCP tools and web research, memory center, agent orchestration.
- DNS health/registry/routing and VLESS/Xray as separately toggled, permission-conscious components.
- Android 13+ permission flows and user-accessible controls, ABI/NDK tests and install-size reduction.
**Acceptance**: feature parity matrix validated against source with device tests; unsupported/experimental behavior labelled.

## Stage 7 — Actions build-to-chat artifact pipeline
- Bind task → PR/commit SHA → Actions run/job → logs → artifact download → native chat attachment.
- Hash/size/version/ABI/signature verification, expiry checks, optional approved GitHub Releases publishing and Android installer confirmation.
- Retry CI only on attributable failures, not infinitely; secrets remain on GitHub/Keystore.
**Acceptance**: successful Android GitHub Actions APK downloadable/launchable directly from GitHubble Chat.

## Stage 8 — stabilization/release
- CI: unit, Android integration, instrumentation, account-switch/permission tests and signed release identity validation.
- Regression across GitHubble billing, Actions, repositories, PRs, settings and themes.
- Only after green validation + review merge feature PR; release versionCode bump, signed APK, release notes, rollback.
**Acceptance**: signed, installable APK under unchanged GitHubble applicationId and signing certificate.

## Phase-1 scope / limitations
- The imported 96-provider OmniChat JSON is metadata, **not a live provider connection**.
- Native ChatGPT OAuth, OmniChat LLM calls, automatic code writing from chat and APK delivery are NOT implemented yet.
- Copilot task launch requires GitHub user access and plan/policy entitlements. Workflow dispatch requires a workflow with workflow_dispatch.
- Phase-one agent GitHub mutations check active-account identity at the call boundary, but a production frozen-account HTTP credential implementation is required for guaranteed multi-account isolation under concurrent account switches.
- GitHub Actions PR APK is a debug test build; release signing happens only through reviewed main workflow.
