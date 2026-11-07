# Git Agent rc4.2.16 — OAuth accounts and conversation drafts

Source: Digger401/OmniChat 407cc6722f52f2e360b8e4449e48d98f2989e509,
OpenAiCodexAccountClient, ClineAccountClient, KimiCodeOAuthRepository and ApiGateway.
The Java adaptation adds three distinct subscription transports without changing API-key IDs.

## Implemented

- Device-code authorization in the user's external browser for OpenAI ChatGPT/Codex,
  Cline Account/Pass (WorkOS), and the global Kimi Code host pair.
- OpenAI's PKCE verifier/challenge validation before authorization-code exchange.
- Pending, slow_down, access-denied, expired-code, cancellation and account-switch handling.
- Encrypted per-GitHub-account sessions, refresh-token rotation, and compare-before-replace
  to prevent an in-flight refresh from restoring a disconnected/replaced session.
- Actual remote model discovery; Cline's live Pass/free recommendations are included.
  No hardcoded model lists imply account entitlement. An authenticated session with an
  unavailable catalog is saved but not declared ready for chat.
- API and subscription model filters, single-tap selection and shared inference path for
  chat, generation and build repair. OpenAI Codex uses the Responses SSE protocol and
  requires response.completed before accepting a final result.
- Fixed HTTPS destination allowlist, redirects disabled, bounded input/output and no
  raw OAuth error body in history or logs. Public OAuth client IDs follow the reference;
  providers may restrict those flows or account plans. No login or live provider call
  is performed by CI; successful device/browser authorization must be tested on-device.

## UI and history correction

The upper Git Agent caption is removed from new chats; real conversation titles remain.
All four execution-mode controls occupy one weighted row with explicit borders and
opaque enabled/disabled colors. Auto and orchestration stay disabled until implemented.
Empty conversations are now pending drafts rather than saved history entries. Repeated
new-chat taps reuse an empty draft. Legacy empty placeholders are hidden, without deleting
stored messages or typed drafts. A first message/action materializes the saved conversation.

## Next work

Gemini Web, Grok and Claude legacy Subscription Proxy adapters; role-based orchestration
with model assignments; then image/file capabilities, OCR, search and plugins. They are
not advertised as available by this change. OAuth transport compilation and fixture tests
are distinct from a live provider authorization/entitlement check.
