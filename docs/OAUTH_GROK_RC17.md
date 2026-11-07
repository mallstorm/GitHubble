# Git Agent rc4.2.17 — xAI/Grok subscription transport

Source: Digger401/OmniChat `407cc6722f52f2e360b8e4449e48d98f2989e509`,
`SubscriptionProxyGrokClient.kt`, `SubscriptionAdapters.kt`, and `ApiGateway.kt`.

## Implemented

- Fourth subscription connection: xAI Grok OAuth, alongside OpenAI, Cline, and Kimi.
- OIDC discovery and device-code authorization through the external browser;
  pending, slow_down, denied, expired, cancelled and account-switch states reuse
  the existing lifecycle-aware login UI.
- Token endpoint retained in the encrypted account-scoped session. Refresh supports
  rotating tokens and responses that retain the previous refresh token. An access-only
  session works until expiry and then explicitly requires a new login.
- Remote model discovery through Grok Build; no default list is used as entitlement.
  Hidden/unsupported/non-Grok entries are excluded. Catalog failure does not mark
  the connection ready.
- Shared text transport for chat, file generation and repair proposals uses the
  Grok Responses endpoint and headers from the reference implementation. SSE
  requires a final response.completed; partial/failed responses are rejected.
- Discovery and refresh destinations pinned to auth.x.ai; authenticated inference
  and catalog requests pinned to cli-chat-proxy.grok.com. No redirects, arbitrary
  endpoints, cookie imports or token display. A discovered endpoint cannot redirect
  a refresh token to another provider in the general host allowlist.

## Validation scope

`GrokSubscriptionTest` provides eight deterministic protocol tests; Android
`GitAgentUiChecks` also verifies Grok session persistence/account isolation and
that disconnect cannot be undone by a late refresh.
Build and device-check outcomes are reported separately by the PR workflow.
No live xAI login, entitlement, billing or model response is established by fixtures.
The public client/endpoint follows the reference and can be restricted by xAI policy.

## Still pending

Gemini Web/Cloud Code and Claude subscription adapters; multi-role orchestration,
automatic build-repair loops and durable task recovery; image/video generation,
OCR, web search, plugins and Live. Grok media generation and billing/plan display
are not included in this text-transport pass.

Version: `1.0.0-rc4.2.17`, versionCode `64`. Work remains on draft PR #22;
main and production signing are unchanged.
