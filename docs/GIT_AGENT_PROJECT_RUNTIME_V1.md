# Git Agent Project Runtime — architecture

## One native model mode
Git Agent uses the selected AI model (Cline/OpenAI/Grok/API) for local chat and Workspace.
An encrypted device-local guest profile works without GitHub OAuth. Linked GitHub
profiles retain their own previously configured models and keys. Repository and
Actions operations require a separately authenticated GitHub session.
There is no embedded Copilot WebView or native Copilot Cloud task adapter.

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

## Actions repair loop (reviewed, not unattended)

After a failed GitHub Actions run, the user can select **Диагностика CI → исправление**
or enter `/repair` / `/repair <run_id>` in Agent mode.

1. GitHubble fetches the failed run status and redacted log excerpts in its
   authenticated native client.
2. A repair ticket requires **completed failure/timed_out**, a matching
   repository, branch, current remote HEAD and local Workspace source revision.
   Legacy/imported Workspaces without tracked source SHA must be re-imported.
3. An explicit, one-time dialog authorizes transmission of diagnostic excerpts
   and relevant local source snippets to the selected AI provider.
4. The model proposes up to 12 complete UTF-8 replacement/new files. The user
   reviews and confirms. A checkpoint and stale-file checks protect local edits.
5. A separate confirmation publishes the Workspace snapshot to GitHub; Actions
   workflow selection is independently confirmed. Results can be checked again
   through the same UI.

**No unattended write/push/build/retry loop** has been enabled. The unified
`recent_build_runs` and `build_run_diagnostics` model tools expose only
GitHub Actions metadata (run status and failed steps). They **never** return
raw or excerpted job logs without the distinct repair-disclosure consent.

Known limits: 180 text files / 8 MiB per Workspace snapshot publication,
12 generated files per repair proposal, incomplete/binary files excluded.
Log secret filtering is heuristic and does not guarantee full confidentiality.
Private-repository OAuth and CI repair still require device-level verification.
