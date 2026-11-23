# GitHubble — полноценная резервная копия репозитория (Experimental)

This feature is **separate** from the lightweight `RepositoryMetadataBackup` ZIP and Mirror Manager. It uses a temporary GitHub Actions workflow in the source repository to generate a **portable, full Git repository artifact ZIP**.

## Create backup

From repository → Архив репозитория → **Создать полную копию репозитория**:
1. Authorize the repository-owning connected GitHub account.
2. Optionally include binary release assets/APKs.
3. GitHubble installs a dedicated workflow file on the source default branch if not already present. This introduces a service commit. This initial change is not part of the captured default branch snapshot.
4. Runner checks original default SHA and refs, clones complete Git history, fetches LFS, builds `repository.bundle`, gathers GitHub metadata (labels, milestones, Issues/comments, PR data, Releases), optionally downloads release binaries, calculates SHA-256 digests, and publishes an Actions artifact ZIP.
5. Native progress follows actual Actions job step status (not an invented byte-level ETA).
6. User downloads ZIP to Android Downloads/GitHubble and **must preserve it off GitHub**. Actions artifacts are temporary. The backup workflow does not restore the original HEAD after staging, and may remain in the source repository.

Contents:
- `repository.bundle` — complete retained Git history and all heads/tags.
- `git-refs.txt` — exact SHA for refs.
- `lfs/objects/**` — fetched Git LFS objects.
- `metadata.json` — GitHub metadata including Issues and PR reference records.
- `release-assets/<asset-id>.bin` — optional release binaries.
- `manifest.json` — SHA-256 digest and size for each entry.

## Restore backup

From **Восстановить полную копию из ZIP**:
1. Android streams and verifies *every* ZIP entry, allowable paths, sizes and SHA-256 hashes. Manifest digests are integrity checks, **not** an author signature.
2. User selects connected destination account and a new repository name. The destination is always newly created and **private**, never an existing repository.
3. The archive is staged in that new private repository as a temporary draft-release asset. Maximum upload is **1.95 GB**.
4. A one-time encrypted Actions secret enables safe import of Git refs containing workflow files.
5. GitHub Actions downloads the exact staged ZIP, verifies all entries again, validates bootstrap destination SHA and the Git ref inventory, transfers LFS, removes the temporary secret, then atomically restores branches and tags with a force-with-lease restricted to the known bootstrap branch.
6. Metadata (labels, milestones, Issues/comments and Releases; optional binary assets) is replayed under the destination's GitHub identity. Original timestamps/author identities, PR reviews and PR discussions cannot be faithfully recreated by GitHub's API; reference records stay in the archive.
7. Client checks the restored main Git SHA and sets the original default branch, then reports **Архив применён и валидирован**.

## Progress labels

- **Создание резервной копии репозитория**
- **Сверка информации**
- **Архив применён и валидирован**
- **Восстановление**

Percentages are completed workflow stages, not an estimate of bytes or elapsed time. A failed/ambiguous mutation is **not retried automatically**. Restore may partially create a repository on failure; inspect it in GitHub first.

## Restrictions and validation

- Backup/restore cannot claim inclusion of GitHub repository secrets, Actions history, team permissions or faithful PR reviews (GitHub APIs do not make those exportable/restorable).
- ZIP is not encrypted. Sensitive projects require protected storage.
- Git submodules are recorded as Gitlink pointers and `.gitmodules` references. External submodule repository objects are NOT embedded in this ZIP. Preserve the corresponding source repositories separately for a completely offline restore.
- Export can exceed the 1.95 GB restore limit; download and external Git tooling remain possible, but native restore cannot accept files above the limit. Large repositories require a separately designed multi-part transport.
- GitHub permissions must allow Actions, Issues, Releases and Contents. Workflow-file updates need appropriate workflow permissions.
- Do not merge until Android unit tests, debug/release, R8 and a real two-repository create→download→restore test pass. The source and destination must be disposable test repositories for the end-to-end test.

**Status:** experimental implementation. CI compilation and an actual GitHub Actions backup/restore run are necessary before a release-ready claim.

CI validation trigger: full-backup tests and Android release build required.
