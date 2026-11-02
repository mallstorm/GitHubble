# GitHubble Copilot Chat + Gemini Antigravity integration design (2026-11-02)

## Verified status

- **GitHub Copilot Cloud Agent**: GitHubble already uses the official GitHub agent
  task API. This starts tasks against an explicitly selected repository, tracks
  their state and PRs, and is **not** an interactive chat inference endpoint.
- **GitHub Copilot Chat**: not connected in the Android app. The GitHub Copilot
  SDK (GA, June 2026) supports true multi-turn conversations, streaming, tools,
  session management and paid-Copilot subscription authentication. The SDK
  requires a compatible Copilot runtime; Android is not an advertised native
  runtime platform. Java 17 SDK availability alone does not guarantee Android
  runtime ABI compatibility.
- **Google Gemini Antigravity**: the imported client still calls the legacy
  Cloud Code 'loadCodeAssist' / 'onboardUser' project discovery. Google stopped
  serving individual Code Assist IDE/CLI requests on June 18, 2026. A Google
  OAuth callback without a Cloud Code project ID is **not** usable authorization
  to call Antigravity's current official runtime.

Official documentation:
- https://docs.github.com/en/copilot/how-tos/copilot-sdk/setup
- https://docs.github.com/en/copilot/how-tos/copilot-sdk/setup/github-oauth
- https://docs.github.com/en/copilot/how-tos/use-copilot-agents/cloud-agent/use-cloud-agent-via-the-api
- https://developers.google.com/gemini-code-assist/docs/deprecations/code-assist-individuals
- https://antigravity.google/docs/cli/install

## Target design: three distinct engines

### A. Git Agent Chat (native Android, already present)

Selected API/OAuth-supported models answer ordinary conversation. Workspace
tool access is read-only and requires an explicit per-account toggle.
Repo creation, file writes and Actions start only after user confirmation.

### B. GitHub Copilot Chat (official SDK, not yet connected)

1. **Copilot runtime host** — use the official Copilot SDK + supported native
   runtime on the user's **Windows OmniCode IDE** or isolated managed backend.
   Never embed third-party reverse-engineered Copilot chat endpoints or claim
   that the Cloud Agent Tasks API exposes free-form chat.
2. **Account-bound authentication** — existing GitHub App user-to-server OAuth
   identity and Copilot subscription are verified using the SDK's official
   account support. SDK sessions may accept user-to-server `ghu_` tokens.
   Never send a GitHub OAuth token to an unpaired or untrusted endpoint.
3. **Transport/security** — Android pairs explicitly with a trusted endpoint
   using a one-time pairing ceremony, HTTPS with certificate validation,
   short-lived scoped application-session tokens and revocation. Never expose
   the raw headless Copilot runtime/JSON-RPC port on the public internet.
   Runtime host owns the session and enforces rate limits, max input, per-user
   isolation, audit metadata and cancellation.
4. **Chat contract** — `POST /v1/chat/sessions` (create), 
   `POST /v1/chat/sessions/:id/messages` (send), streaming events
   `assistant.delta`, `tool.request`, `tool.result`, `session.error`,
   `session.end`; `DELETE /v1/chat/sessions/:id` for lifecycle. Every turn
   carries a stable request-id, pinned GitHub identity and model selection.
   Persist local context with `AgentProjectStore` and map SDK session IDs to
   account + conversation (no cross-account/session leakage).
5. **Tool permission broker** — deny writes, shell, network tools and arbitrary
   MCP by default. User must grant narrowly scoped operations per execution.
   GitHub mutations remain pinned-HEAD + explicit user approval. No
   'approve all' default and no implicit PR creation on chat messages.
6. **UX** — model selector differentiates clearly:
   - `Git Agent Chat` (ordinary dialogue through configured model);
   - `GitHub Copilot Chat (SDK)` (multi-turn dialogue, only when paired);
   - `GitHub Copilot Cloud · tasks` (repo-based execution and PR).
   Assistant replies must indicate actual engine and authentication state.
7. **Tests** — local/remote pairing, cancellation, session isolation, context
   resume, streaming reconnection, denied tools, no accidental writes,
   permission expiry, offline behavior, usage counters and UI routing.
8. **Cost** — Copilot Chat requires an eligible Copilot plan/enterprise policy
   as documented by GitHub. A ChatGPT subscription does not grant Copilot.
   Billing/quotas must come only from official GitHub endpoints if available.

**Implementation sequence**: (1) trusted runtime host + pairing
(2) Android streaming connector and account-bound session store
(3) tool broker and permission prompts
(4) model selection, usage and recovery
(5) integration tests on Android 13 and real device.

### C. Copilot Cloud Tasks (already present)

Maintain existing GitHub Agent Tasks REST integration. Add an explicit mode
and prompt confirmation; do not route greetings/ordinary chat to task creation.
Repository and GitHub authorization are required before any task attempt.

## Target design: Gemini Antigravity

- **Personal account:** official Antigravity OAuth-personal flow within a
  supported Antigravity runtime/SDK. Old Cloud Code OAuth code is marked
  legacy, not silently retried, and is not falsely reported as completed.
- **Gemini API:** existing separate direct API-key connection continues to
  work as its own provider. Do not describe it as Antigravity subscription
  access or bill it to a Google AI subscription.
- **Enterprise:** official Antigravity Enterprise / GCP project + region
  authorization where supported; an arbitrary project ID must not be invented
  as a personal-account fix.
- **Integration choices:** reuse the same trusted companion architecture if
  Android cannot host the official runtime, or add a native officially
  supported Android runtime if Google later provides one.
- **Validation:** personal vs enterprise accounts; failed OAuth, stale scope,
  entitlement and project discovery, explicit plan/quota report and token
  cleanup on disconnect. Never log/copy access or refresh tokens to history.

## Implemented in rc4.2.23

- Copilot Cloud composer is clearly labeled as **Tasks, not Chat**.
- Greetings/ordinary questions never trigger Cloud Agent tasks.
- Explicit task wording or `/copilot` is required before Cloud Agent confirmation.
- Selection screen explains the SDK Chat integration status.
- Legacy Google Cloud Code sign-in is intercepted with a clear explanation
  and options for official Antigravity documentation or direct Gemini API.
- No dummy Copilot answers, invented GCP project IDs or fabricated balances.

## Not yet implemented

A working GitHub Copilot SDK runtime host, secure phone pairing,
streaming Copilot Chat sessions, the official Antigravity runtime connector,
and verified live subscription entitlements. All of these require
compatible runtimes/account authorization and must be separately tested.
