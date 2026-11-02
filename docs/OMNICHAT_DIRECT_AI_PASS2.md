# OmniChat direct AI connections: integration pass 2 (2026-11-02)

Branch: `feature/omnichat-native-agent-phase1`, PR #22. This is an incremental integration, not full OmniChat parity.

## Implemented source
- `agent/OmniChatConnectionStore.java`: account-scoped connection selection, per-provider model ID, encrypted API keys in `SecureStore`/Android Keystore, disconnect/revoke.
- `agent/OmniChatChatClient.java`: real HTTPS JSON request/response engine, bounded multi-turn context, OpenAI Responses API and OpenAI-compatible Chat Completions, fixed approved provider hosts and no redirects, safe bounded error display, dynamic model discovery.
- `ui/AgentChatScreen.java`: native GitHubble account connection dialog, model selection and provider switch, project chat with Chat/Agent and Copilot selector, new conversation.
- `MainActivity.java`: async authenticated responses, account binding, saved conversation, error reporting; existing GitHub repo/create/actions/coplilot commands remain separate and require confirmation.
- `OmniChatTransportTest.java`: API endpoint allowlist, unsupported host rejection, response parser unit tests.
- Digger401 OmniChat 96-entry provider metadata remains in `assets/omnichat/provider_catalog.json` and is not a claim of 96 working connections.

## Supported direct API-key providers
OpenAI API (Responses), Gemini via documented OpenAI-compatible endpoint, OpenRouter, Groq, DeepSeek, xAI, Mistral, Together AI, Cerebras and NVIDIA NIM (Chat Completions). API credentials are entered by the user and not included in source/history/normal settings. Model IDs are editable and can be discovered via provider's native /models API where available.

## Not yet implemented
- **ChatGPT subscription OAuth** / Sign in with ChatGPT, Codex app server, Cline/Subscription Proxy, and provider-specific OAuth: not interchangeable with API keys.
- OmniChat native `ApiGateway.kt` and `ChatViewModel.kt` full port, streaming/voice/Live, images, provider model capability filters, quotas and billing.
- Automatic model selection, function/tool calling, generated file writing and self-healing GitHub Actions tasks; current GitHub commands are explicit and confirmation-gated, not autonomous.
- Robust Room-backed history, multiple conversations per project, native Diff/PR agent action approval.
- Real-device provider authentication, plan eligibility and traffic verification. Passing tests and compiling APK does not verify a remote subscription.
- Signed production APK: PR CI builds `.debug` variant; production signing stays tied to reviewed `main`.

## Security and correctness
- Only fixed HTTPS endpoints, no user-entered base URLs, no cross-host redirect following.
- Credentials partitioned by selected GitHub account, encrypted in Android Keystore-backed SecureStore. Each provider has distinct secret name. Switching GitHub account changes selected credentials.
- API chat is not automatically given raw GitHub tokens; repository/project names are included in AI context and user messages are transmitted to the selected provider.
- Previous chat turns may be sent to newly selected provider for conversation continuity; UI discloses this. No agent tool write permission is granted to plain API chat.
- Live model availability and quota may differ from advertised model IDs.

## Acceptance tests
1. Gradle unit tests + debug/release compile must succeed in PR CI.
2. User must connect GitHub account, enter a valid provider API key and model, and verify real responses on Android device.
3. Switch GitHub account and verify the connection is not silently reused.
4. Confirm GitHub Copilot executor and repository/actions controls still work.
5. Verify key revocation, empty/bad key, HTTP 401/403/429, timeout, unsupported model, and behavior without network.
