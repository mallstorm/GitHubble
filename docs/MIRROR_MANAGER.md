# GitHubble Universal Mirror Manager

Mirror Manager supports any two connected GitHub accounts, in either direction, without a fixed master account or a permanent mirror controller.

## Pair-first experience

- Open any repository and choose Mirror Manager. If in a known linked pair, the UI shows the counterpart with Send, Receive and Compare.
- Send uses the opened repository as Git source. Receive uses its linked counterpart as Git source. Account identity is resolved from repository ownership, not the currently active account.
- Multiple independent linked pairs can share one repo, e.g. mallstorm/OmniChat with marwell4/OmniChat and with ntl-nam/OmniChat. Pair IDs are direction-independent, based on case-normalized repository names.
- Verified OneShot copy history is migrated into a separate local persistent pair registry. Deleting the operation journal does not delete links.
- After deleting app data or reinstalling, use Link existing repositories: choose the second account and repository and verify at least one identical Git commit within the last 500 commits on both repos. This recovery does not mutate any remote repository.
- The registry is local, NOT cloud-synced. We do not silently require Administration:write, alter repository topics or write metadata files into user projects just to remember pair membership.

## Transfer policy

- COPY: new independent repository with full Git heads, tags, LFS, no Fork.
- SEND/RECEIVE when fast-forward: compare default Git commits and synchronize all source refs atomically without force or prune. Destination-only refs are kept, conflicting shared tags and non-fast-forward branches abort.
- SEND/RECEIVE when divergent: create a new review branch mirror-return/requestId in the destination and a draft Pull Request. Destination default branch is never automatically overwritten. User reviews conflicts and merges manually.
- Divergent histories with identical file trees do not create meaningless PRs. Tags/side branch-only differences may require a later dedicated workflow.
- Each operation needs explicit confirmation and separate credentials for the two GitHub accounts.

## History and security

- Jobs retain linkPairId, original copy ID, source/target SHAs, direction, run URL, cleanup and optional PR state. The journal shows operations from either side.
- No force push, prune, deletion or silent merge of an existing target.
- Source permissions: Contents:write, Workflows:write, Secrets:write and Actions:write. Return PR additionally needs Pull requests:write on the destination. Branch rulesets can block temporary workflow commits.
- The destination OAuth token is encrypted into a temporary GitHub Actions secret in the source repository. Worker removes it after completion. Secret deletion does not revoke OAuth credentials, so repository workflow writers must be trusted.
- Temporary source workflows leave two technical commits in main even after cleanup.
- Real end-to-end testing of the new pair-first send/receive and PR flow remains outstanding; Android CI validates compilation, tests and generated Actions workflow syntax.