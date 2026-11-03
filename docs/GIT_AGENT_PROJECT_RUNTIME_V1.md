# Git Agent Project Runtime — architecture

## Two modes
1. Connected AI model: persistent native Git Agent Chat/Workspace with provider selection (Cline/OpenAI/Grok/API), GitHub OAuth and confirmed native actions.
2. Copilot Web: separate WebView at https://github.com/copilot; no native task adapter, token injection or tool bridge. The web session is independent from GitHubble OAuth and can require separate browser sign-in.

## Local / remote project
Local Workspace exists without GitHub. Linked GitHub repository is a remote projection and source of version history. Existing import currently copies only a bounded text snapshot, not a full .git clone. Uploads and destructive operations require confirmation.

## Further implementation
Unified Project Runtime tool broker, deterministic capability negotiation, project event journal, source synchronization and isolated test runner. A successful CI build does not prove website login or real GitHub access; test those separately.

## Unified Workspace publishing

A confirmed `/workspace push` now prepares a bounded **180-file / 8 MiB**
text snapshot, displays skipped files, checks the pinned GitHub HEAD and
Workspace content, then performs **one Git Data API tree + commit + non-force
ref update**. The 12-file limit remains for AI-generated modification plans,
not for user-approved snapshot publication. Credentials, local configuration,
binary and oversized files are excluded. This is a bounded source snapshot,
not a full Git clone or unrestricted synchronization. Builds are suggested
after publication but still require their own confirmation.
