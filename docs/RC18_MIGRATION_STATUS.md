# rc4.2.18: routing, subscriptions, memory and Workspace

Reference: Digger401/OmniChat `407cc6722f52f2e360b8e4449e48d98f2989e509`.
Host: mallstorm/GitHubble draft PR #22; main is unchanged.

## This pass

| Area | Implementation |
| --- | --- |
| DNS/routing | Original NetworkDnsPolicy, DnsRegistryManager, DnsRouteStore/Scorer, DnsHealthEngine and AiServiceResolver. System/Smart/DoH/DoT, local domain/service profiles, remote registry, per-network statistics, hysteresis/leases, TLS/HTTP reachability and periodic checks. All active API-key and OAuth transports share this layer. |
| Xray | Original InAppVlessRouter/restorer and libxray 26.6.27. Encrypted profiles, VLESS URI import and Xray JSON import, local SOCKS/HTTP listeners, foreground/process restoration. Failed/restarting routes stay blocked until disabled/recovered. Native runtime startup is explicitly exercised by instrumentation. |
| Subscriptions | Existing OpenAI/Cline/Kimi/Grok plus original browser OAuth clients for Gemini Antigravity/Cloud Code and Claude Code. Loopback state/PKCE handling, account-scoped encrypted persistence and refresh. Gemini Web cookie sessions are a separate, unported transport. |
| Cline groups | Live recommendation Free / Pass / Billing categories retained and displayed as independent filters. Object-map catalogs, free IDs without :free, and a working recommendation endpoint with a failed generic catalog are supported. No fabricated fallback model IDs are marked available. Refresh models on an older connection to populate categories. |
| Conversation scope | Repository/workspace selection is per conversation. New chats inherit neither. Ordinary chat omits repository scope from inference. Repository binding uses an explicit action or /repo owner/name in Agent mode. Legacy global project state migrates to its current conversation once. |
| Workspace | Local projects without a repository. Original WorkspaceTools, ProjectFileSystem (internal/SAF), ProjectIndex, ProjectWorkspaceSupport, archive/artifact policies. Original index/refactor/checkpoint engine is exposed through native host controls: files/editor, search, symbols, map, move/delete/mkdir/rename, snapshots/restore, ZIP, selected device folder. An Agent project-creation request creates a local planning workspace. File generation writes locally only after preview/approval. |
| Memory | Original MemoryPolicy parser/privacy/lexical scorer, adapted account/project-scoped SQLite service. Manual remember/forget, durable extraction via selected model, episodic recall/backfill, per-project scope, search/edit/pin/delete, JSON import/export and recall controls. Credentials never go into memory. Automatic learning makes an extra provider request and can be disabled. |

The source memory database used Room/OmniDao; GitHubble uses an independent SQLite schema.
Existing OmniChat app data is not automatically copied between application packages.
Workspace and memory UIs are native Views adaptations, not pixel-equivalence claims.

## Still missing from complete OmniChat parity

- Full AgentEngine tool-call loop across all providers; current code generation uses validated,
  approved file batches. Imported Workspace tools are available to the native controls;
  arbitrary model-driven filesystem/network tool execution is not advertised as complete.
- Multi-role orchestration and automatic execution; automatic bounded CI repair/rebuild loops
  with durable resume after process death.
- Full DeveloperWorkspaceScreen layout, conflict-aware editor UI, completion/diagnostic panels
  and a unified task ledger. The original project index/refactor/checkpoint engines are present.
- Device-wide filesystem index/permission-broker tools; project folder access uses explicit SAF.
- WorkspaceZipAgentPipeline's attachment-to-agent workflow and multimodal artifact workflows.
- Gemini Web login/session/traffic bridge; other account integrations outside these six OAuth
  clients, plus provider usage/quota/billing UI. Model catalog discovery is not proof of a plan.
- Camera/gallery vision, OCR, image/video/music generation; full previews and media routing.
- Web search/cloud browser UI, MCP/plugins, Live/conference, speaker profiles and advanced voice.
- Full Room migration of old chat history and provider-independent context compaction. Current
  chat retention remains bounded; this pass does not create infinite context.
- Production-signed delivery from this draft feature branch. Repository policy signs only main;
  no merge or signing-policy change is made in this pass.

## Validation

Unit tests cover DNS normalization/scoring, memory policy, project index/archive policy,
transport body/header/status preservation, Cline grouping, workspace account/path isolation,
OAuth protocols and existing regressions. Android 13 checks cover independent chat bindings,
memory isolation/privacy, native Xray startup/listeners, new settings screens and existing UI.
Actual outcomes are reported with the final CI run, not inferred from test source alone.
Live browser authentication/entitlements, user Xray server connectivity, private DNS service
reachability and SAF document providers still require real-device/account verification.
