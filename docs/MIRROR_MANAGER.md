# GitHubble Mirror Manager — OneShot, update, return

This module supports three account-scoped Git transfers through a temporary GitHub Actions workflow in the current source repository. It does not use Fork or a permanent controller.

## Modes

1. COPY. Create a NEW independent empty repository on another connected account. Transfer Git history, branches, tags and LFS. Refuse if destination already exists.
2. SYNC. From History, select Update mirror on a VERIFIED copy. The original source and destination are reused. Git refs and LFS are transferred to the existing independent destination only with fast-forward branch pushes. Existing destination-only tags and branches remain untouched; conflicting common tags or non-fast-forward common branches cause failure. Force, prune, destructive reset and deletions are forbidden. Verify every source ref is present at the identical SHA after transfer.
3. RETURN. From History, select Return changes on a VERIFIED copy. Source becomes its existing mirror and destination becomes the former original. If Git trees have not changed, refuse the unnecessary operation. Push only a new review branch in the destination named mirror-return/<requestId> and create a DRAFT pull request against its default branch. GitHub checks conflicts. No automatic merge or modification of the destination default branch. Users explicitly review and merge on GitHub.

## Permissions

Source: Contents:write, Workflows:write, Secrets:write, Actions:write, permission to write temporary workflow on the default branch.
Destination: Contents:write and Workflows:write. COPY requires creating a repo. RETURN additionally requires Pull requests:read and write.
The connected OAuth sessions are separate, even when the user has more than two GitHub accounts. No GitHub App PEM, Client ID or separate mallstorm controller is required.

## Transfer security

- Before adding the temporary workflow, persist source and destination default branch SHA baselines.
- The source's temporary Actions workflow is created and dispatched using source account authorization, with contents:read run permission. Its own workflow commit is not included in the transfer snapshot; local bare Git refs are reset to the pre-workflow SHA.
- The destination OAuth credential is sealed with libsodium crypto_box_seal for the GitHub Actions Repository Secret public key. It must not be logged, placed in a workflow input, persisted to SharedPreferences, or included in the workflow source.
- The runner compares existing destination refs before syncing and uses NO force, prune or deletion. When a target-only branch/tag exists, preserve it and verify the source ref subset afterward.
- Return operations verify a shared Git ancestor, the destination default SHA and the new review branch commit. The app creates a draft PR after GitHub Actions reports successful completion.
- After the run finishes, the app and WorkManager remove the temporary credential secret and workflow. Long network loss or uninstall can delay removal; manually use Check where necessary. Deleting the secret is not a token revocation.
- Creating/removing a workflow produces two technical Git commits on the source default branch; subsequent syncs may carry earlier technical commits.
- Branch protection, rulesets, LFS custom endpoints and resource limits can prevent an operation. The implementation stops without force.

## Not included

Issues, pull requests (other than the new review PR), Releases assets, Actions logs, GitHub secrets, branch protection, collaborators and non-Git project settings are not mirrored. RETURN stages only the default-branch snapshot, not all other refs.

## Operation history

The local journal records transfer direction, mode, linked original copy, SHA snapshots, Actions run, cleanup state, PR URL/number and merged/closed state. History is stored on-device (SharedPreferences), not in GitHub cloud or another permanent controller. Do not uninstall the app until pending secrets/workflows have been cleaned up.

## Validation

Android CI compiles the app and tests its workflow generation. Inter-account end-to-end testing of repeated synchronization and draft PR return is still required before declaring production readiness.
