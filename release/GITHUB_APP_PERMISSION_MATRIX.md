# GiHubble permission matrix for GitHubble 1.0 RC

| Function | GitHub App permission | Access |
|---|---|---|
| Profile and email | Account: Email | Read |
| Follow/unfollow | Account: Followers | Read/write |
| Star/unstar | Account: Starring | Read/write |
| Repository content | Repository: Contents | Read/write when editing/import is enabled |
| Repository administration | Repository: Administration | Read/write for create settings, visibility changes and deletion |
| Workflow files | Repository: Workflows | Read/write only when importing or editing `.github/workflows/*` |
| Issues | Repository: Issues | Read/write |
| Pull requests and reviews | Repository: Pull requests | Read/write |
| Discussions | Repository: Discussions | Read/write |
| Projects v2 | Account/Organization Projects | Read/write only where required |
| Commit checks/statuses | Checks and Commit statuses | Read |
| Metadata | Repository metadata | Read |

GitHubble must degrade to read-only UI when a permission is unavailable and must display the GitHub API error instead of fabricating success.
