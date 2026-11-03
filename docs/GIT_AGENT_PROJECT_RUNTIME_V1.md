# Git Agent Project Runtime — architecture

## Two modes
1. Connected AI model: persistent native Git Agent Chat/Workspace with provider selection (Cline/OpenAI/Grok/API), GitHub OAuth and confirmed native actions.
2. Copilot Web: separate WebView at https://github.com/copilot; no SDK, cloud-task agent, token injection or native tool bridge. The web session is independent from GitHubble OAuth and can require separate browser sign-in.

## Local / remote project
Local Workspace exists without GitHub. Linked GitHub repository is a remote projection and source of version history. Existing import currently copies only a bounded text snapshot, not a full .git clone. Uploads and destructive operations require confirmation.

## Further implementation
Unified Project Runtime tool broker, deterministic capability negotiation, project event journal, source synchronization and isolated test runner. A successful CI build does not prove website login or real GitHub access; test those separately.
