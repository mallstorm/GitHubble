# External repository import (independent, no Fork)

## Entry point

Open any public GitHub repository from global search, then:

**Additional actions → Create copy**.

The dialog selects a connected destination account, repository name, and visibility (private by default). No changes are made to the original repository.

## Operation

1. Verify that the source is public and read its default branch and pinned commit SHA.
2. Check for a destination name collision; create a NEW non-fork repository with a temporary bootstrap commit.
3. Write a temporary workflow into that new repository and dispatch GitHub Actions there (not in the original repository). The connected destination account's token is sealed using the repository's Actions public key and installed as a per-run Actions secret. The runner needs this credential because the standard Actions GITHUB_TOKEN cannot push Git refs containing workflow files.
4. On the runner, clone the public Git repository as a bare repo; verify source head and that destination still contains only the bootstrap branch. Refuse repositories with custom LFS config. Copy LFS objects, heads and tags, then compare remote refs.
5. The app's **Check import** action reads the Actions result. On success, verify default-branch SHA, switch the destination default branch if necessary, and delete the bootstrap branch only if it was not present in source.

Git history and commit authorship are preserved; GitHub does NOT mark the destination as a fork. Issues, PRs, Releases, secrets, permissions and repository settings are not copied. License conditions remain in force.

## Permissions and limitations

- Source must be a **public** GitHub repository with an existing default branch.
- Destination account must have permission to create a repository, write a temporary workflow, dispatch Actions, and change its default branch if necessary.
- The default Actions GITHUB_TOKEN is insufficient for writing workflow files. A connected account token with Workflows:write and Secrets:write is sealed in an Actions secret for the run. Its removal is attempted before pushing third-party workflow files and after completion.
- Git LFS quotas, branch protection and Actions availability can cause import failure. Failure does not overwrite an existing repo and does not auto-delete a newly created destination.
- Import jobs are recorded in app preferences, so Actions work proceeds if the app closes. Open the destination repo and choose **Check import** to verify and finalize the default branch.
- This operation copies Git objects only and does not waive conditions of the source license.
- End-to-end runs with a real external source and newly created destination remain to be verified.

## Retry after an interrupted/failed copy

Open the newly created destination and select **Check import → Retry copying**. The importer refuses to overwrite the destination unless its default branch still equals the original bootstrap SHA and it has no additional branches/tags. The failed REA case was caused by denied workflow writes under the default Actions token; this is fixed by the sealed workflow-authorized account credential. A real retry still requires appropriate Workflows and Secrets write permissions on the GitHub App installation.

## Lost local import history: recover from GitHub Actions

If GitHubble is reinstalled or the user switches between Debug and Release builds, the local import journal may be unavailable. On any repository belonging to a connected account, the repository overview checks for the GitHubble import workflow and a matching workflow_dispatch run. Only verified matches receive the **Check import** tile; normal repositories do not.

Selecting the tile recovers the original request identifier, job status, bootstrap branch/SHA and destination account from the GitHub Actions metadata. For new imports a lightweight source marker stored in the bootstrap branch supplies the original public source. For older imports, GitHub Actions REST does not expose workflow_dispatch inputs, so the user confirms the exact `owner/repo` of the source once. The client validates the source and pristine destination before allowing **Retry copying**.

No other repository or existing user changes are overwritten, and the destination is never deleted automatically. New retries get a fresh unique request ID to avoid matching an older failed run.
