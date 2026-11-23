# GitHubble — File Compare & Selective Draft PR

## New capability; no duplication

Mirror Manager already synchronizes complete Git histories, checks ancestry and linked pairs. This module introduces FILE-level diffs and user-selected changes only.

Repository → Дополнительные действия → Сравнить файлы:

1. Pick the connected source account, then destination account and existing repository.
2. Compare the two repositories' default-branch Git trees using blob SHAs and file modes.
3. Review source-only, changed and destination-only files; destination-only entries are never deleted.
4. Select up to 12 safe files, max 1 MiB per file and 4 MiB total.
5. Confirm creation of a separate review branch and Draft Pull Request in the destination.

No force push, auto-merge or mutation of either repository's default branch.

## Implementation

- RepositoryFileComparison — deterministic pure tree delta planner and unsafe-path checks.
- RepositorySelectiveTransfer — account-isolated authenticated GitHub API; source and target SHA preconditions; blob checksum verification; Git Trees/Commits/Refs API; Draft PR.
- RepositoryComparisonScreen — native selection, progress and review UI.
- RepositoryFileComparisonTest — unit tests for differences, truncated trees, forbidden file types and duplicate selections.

## Restrictions and safety

Requires Contents read access on source and Contents write plus Pull Requests write on target. Target owner must equal the selected connected account. A private source cannot be copied to a public destination.

Do not select repository workflows, credentials, symbolic links, submodules, or >1 MiB files. LFS pointer files are rejected when applying the transfer; no broken LFS references are silently imported.

A GitHub response with truncated trees is treated as an error. Branch heads are checked before and after copying. Any failure after the PR branch was created is shown to the user with the branch name so they can inspect it before retrying.

The parser limits each tree to 15,000 entries. First 200 differences are rendered; all unrendered differences remain unselectable.

## Future: distinct functionality only

Directory-scoped comparisons and pagination; LFS-aware selective import; CI Doctor root-cause analysis for failed Actions. Existing Mirror Manager, Git Agent, Build Center and backup screens remain unchanged.
