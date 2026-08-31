# GiHubble permission matrix for GitHubble 1.0 RC4

| Function | GitHub App permission | Access |
|---|---|---|
| Profile and email | Account: Email | Read |
| Account plan / Actions billing usage | Account: Plan | Read |
| Follow/unfollow | Account: Followers | Read/write |
| Star/unstar | Account: Starring | Read/write |
| Repository content | Repository: Contents | Read/write when editing/import is enabled |
| Repository administration | Repository: Administration | Read/write for create settings, visibility changes and deletion |
| Actions workflows and runs | Repository: Actions | Read |
| Workflow files | Repository: Workflows | Read/write only when importing or editing `.github/workflows/*` |
| Issues | Repository: Issues | Read/write |
| Pull requests and reviews | Repository: Pull requests | Read/write |
| Discussions | Repository: Discussions | Read/write |
| Projects v2 | Account/Organization Projects | Read/write only where required |
| Commit checks/statuses | Checks and Commit statuses | Read |
| Metadata | Repository metadata | Read |

## Private repositories

A GitHub App user access token sees a private repository only when **both** conditions are true:

1. the signed-in GitHub account itself can access the repository;
2. the installed GitHub App **GiHubble** has Repository access to that repository.

Owning a private repository does not bypass the GitHub App installation selection. If a private repository is missing in GitHubble, open **GitHub → Settings → Applications → Installed GitHub Apps → GiHubble → Configure** and set Repository access to **All repositories** or explicitly select the required private repositories. After changing access, reopen/sign in to GitHubble so the list is refreshed.

## Quota dashboard

RC4 reads the normal REST API rate limit and can show Actions monthly usage when **Account: Plan (Read)** is granted. It reads workflow/run health when **Repository: Actions (Read)** is granted. If either permission is unavailable, GitHubble must show a limited/read-only state rather than fabricate usage values.

GitHubble must degrade to read-only UI when a permission is unavailable and must display the GitHub API error instead of fabricating success.
