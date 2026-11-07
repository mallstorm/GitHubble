# Git Agent ↔ GitHub access: rc4.2.24

## Root causes fixed

1. Models selected via API keys or OAuth subscriptions were invoked through
   \`OmniChatChatClient\` without any GitHub tool bridge. A selected model could
   not enumerate repositories, inspect linked project metadata or invoke
   authenticated GitHub API functions. It responded from text alone, such as
   claiming the account was not connected.
2. The local \`GitHubAgentBridge\` already had a scoped GitHub token for explicit
   Cloud Agent and repository creation, but was not offered to models as
   a read-only tool. AI auth and GitHub auth are separate credentials.
3. Creating or attaching a repository rewrote \`AgentProjectStore.Project\`
   through a four-argument constructor and **cleared workspaceId**.
4. The selected Workspace file paths and README/CONTEXT were being appended
   to model prompts even if the UI's workspace file-reading toggle was off.

## Implemented

- \`GitHubReadLoop\` handles explicit repository list/count/identity questions
  by **calling the GitHub API**, without wasting AI model calls or making up
  data. \`/repos\` and \`/github whoami\` are also available. Auth failures
  show an actionable GitHub sign-in/permissions error.
- Model-agnostic read-only GitHub tools in Agent mode, gated by a new
  **off-by-default** "Чтение GitHub моделью" setting: \`github_account\`,
  \`list_repositories\`, \`repository_info\`, \`repository_files\`,
  \`repository_workflows\`. The app executes approved tool types with its
  own account-pinned GitHub API token. Tokens are never supplied to LLMs.
- Native account status and repository browser in
  **Settings → Git Agent и Workspace**.
- Repository creation from natural language or the Settings panel invokes
  an explicit confirmation dialog. No LLM tool can commit directly.
- Deletion of an owned repository requires a separate screen, exact typed
  \`OWNER/REPO\` confirmation, and another explicit destructive action.
  GitHub may deny this if \`Administration: write\` is missing.
- Publishing a linked local Workspace uses a validated local file plan,
  excludes dotfiles and common credential/keystore paths, limits the batch
  to 12 UTF-8 text files/240 KiB, inspects the actual branch HEAD, prompts
  for review and commit confirmation, and performs a non-force update.
  Larger projects need further Git synchronization support. No remote
  upload occurs until explicit user approval.
- Both GitHub repo creation and repo attachment preserve the local
  \`workspaceId\` link instead of destroying the project association.
- Workspace files are no longer included in model prompts while the
  separate user consent toggle is disabled.

## GitHub App permissions to verify

For a GitHub App user-to-server OAuth token (\`ghu_...\`), the OAuth
\`scope\` field is intentionally empty. Permissions come from **GitHub App
registration + app installation + end-user access**, not from adding the
\`repo\` or \`delete_repo\` OAuth scopes to its Device Flow request.

- Repository metadata: **Metadata: read**.
- Source inspection and authenticated Git Data writes:
  **Contents: read/write** respectively.
- Creating a personal repository: **Repository creation: write** (new
  least-privilege permission, October 2026) or **Administration: write**,
  subject to API/platform account and installation constraints.
- Deleting the user's own repository: **Administration: write** plus
  repository admin ownership.
- Other capabilities (Actions, workflows, PR) require their own
  corresponding permissions. A successful account login proves only
  identity; it does not prove permissions for every operation.

Sources: https://docs.github.com/en/rest/repos/repos
https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app
https://github.blog/changelog/2026-10-13-github-app-improvements-for-least-privilege-and-management/

## Deliberate remaining limitations

- This is a bounded allowlisted read-only tool loop, not an unrestricted
  remote computer or universal MCP host.
- Deletion cannot be simulated or used without the actual GitHub permissions.
- Large multi-file workspace import/push, branch creation, automatic PR,
  and retries across protected branches are separate development work.
- OAuth subscriptions do not magically inherit GitHub App permissions;
  missing GitHub authorization must be resolved by explicit GitHub sign-in
  and installation permissions.
- A real-device test is required for authentication and permissions
  because CI's guest-mode emulator has no user's GitHub secrets.
