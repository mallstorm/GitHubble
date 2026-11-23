# GitHubble — Repository Backup: portable metadata ZIP + Mirror Manager

## Scope: only missing functionality

GitHubble already has Mirror Manager (independent Git history, branches, tags and
LFS between connected accounts). It also has portable app-settings backup, which
does NOT back up repositories. Do not duplicate either subsystem.

This new module backs up GitHub's **repository metadata** into a portable,
versioned, integrity-checked ZIP kept outside GitHub. Together with a previously
verified independent Mirror Manager copy, it forms the two complementary parts
of a recoverable repository.

From the repository overview, open **Дополнительные действия → Архив репозитория**:
1. **Mirror Manager · Git / LFS** — use the existing complete Git-copy flow.
2. **Экспортировать GitHub-метаданные в ZIP** — read metadata and save via Android
   Storage Access Framework (SAF), e.g. Google Drive, local storage or SD card.
3. **Восстановить метаданные из ZIP** — only for repositories owned by a
   connected account, with permission to write.

## What the ZIP actually contains

Format `githubble-repository-metadata-v1`, includes manifest.json,
SHA-256 hash and byte length for each required section:
- repository.json — selected repository settings (not credentials);
- branches.json, tags.json — Git ref metadata (not Git objects);
- labels.json, milestones.json — reusable GitHub configuration;
- issues.json, issue_comments.json — issues, comments and their original metadata;
- pulls.json, pull_comments.json — reference record of Pull Requests;
- releases.json — release metadata and asset descriptors (not binaries).

All list endpoints are paginated to 1,200 entries per section; exceeding the
limit cancels export instead of silently exporting only a prefix. A changed
default branch HEAD also cancels export. The snapshot of mutable GitHub
metadata is not atomic; items may change while pages are fetched.

ZIP files are **NOT encrypted**, and could contain private issue bodies,
security reports or personal data. Keep private backups in protected storage.
Saving the file outside the original GitHub repository is essential: GitHub
Actions artifacts stored solely in the deleted source repo would disappear too.

## Restore rules

Before writing GitHub data, the app:
- verifies all ZIP entries and SHA-256 checksums; rejects unknown entries,
  duplicates, malformed files and overlarge/untrusted ZIPs;
- checks the chosen destination belongs to a connected GitHub account and has
  write permission; **the original source repository is allowed** for additive
  recovery of missing metadata (e.g. after accidentally removing labels);
- same-source restoration never rewrites surviving metadata: existing labels
  and milestones are skipped, Issues are recreated only when zero Issues remain
  in the destination and the user opts in separately;
- **blocks** a private archive going into a public repository;
- previews exact numbers of missing / existing labels and milestones;
- restores only missing labels and milestones, never overwriting existing
  names/titles;
- optionally recreates up to 200 issues and associated comments, only in a
  target with **zero existing Issues** and after separate user confirmation;
- records original issue URLs, authors and dates in recreated issue bodies;
  marks originally closed issues closed, after comments are created.

GitHub does not allow forging original issue authors/created timestamps,
issue/PR numbers or reviews. Recreated issues receive new numbers and authors.
Pull Requests, reviews, release binaries and GitHub Actions execution history
are reference-only in this archive; they are NOT reconstructed. The module does
not claim otherwise. There is no automatic retry after partially successful
GitHub mutations; a failure reports what was created and requires inspection.

## File and endpoint safety

- SAF lets the user explicitly choose where the ZIP is saved or restored from.
- 24 MiB maximum ZIP storage size, 12 MiB per entry, 36 MiB decompressed total.
- Only declared JSON entries are accepted (no path traversal or arbitrary ZIP
  extraction). SHA-256 checks guarantee payload integrity; they are not a
  signature proving who created the file.
- GitHub OAuth tokens are never written to the archive.
- The service uses the existing account-isolated GitHub API client rather than
  introducing another auth/session system.
- Unit tests cover manifest roundtrip, tampering, missing sections and unsafe
  ZIP paths; Actions verifies Android Debug/Release compilation and R8.

## Still outside the scope

Server-hosted scheduled immutable Git bundle backups, incremental binary LFS
archives, encrypted offsite backups, and full-fidelity GitHub PR/Release recovery
are separate projects; do not promise them based on this metadata archive.

## Same-repository recovery (fix)

A ZIP exported from `mallstorm/Telegram-Premium` can be selected while viewing
that *same* repository. Preview first compares labels and milestones by normalized
names/titles. If nothing is missing, choosing Restore without Issues makes no
GitHub API mutations. If some data was removed, only missing labels or milestones
are recreated. Existing Issues are never duplicated; with any current Issues the
optional recreation is disabled, even if some old Issues are missing. No branch,
tag, release binary or Pull Request is modified by metadata recovery.

Validation: same-repository metadata recovery is covered by RepositoryMetadataBackupTest and must pass Android/Release checks before merge.
