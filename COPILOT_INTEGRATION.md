# GitHubble Copilot Cloud Agent integration

## Scope

GitHubble integrates the official GitHub Copilot cloud-agent APIs directly from the Android client. No Copilot CLI, embedded model, Client Secret or private backend is used.

## Entry points

- Repository page → **Copilot**
- Issue page → **Поручить Copilot**
- Activity page → **Copilot Cloud Agent**

## Features

- Check whether `copilot-swe-agent` is assignable for a repository.
- Start a task with prompt, base/head refs, model, custom agent and optional Pull Request creation.
- Quick templates: bug fix, feature implementation, tests and documentation.
- List tasks for one repository or the authenticated user's accessible repositories.
- Filter active and completed tasks.
- Display states, sessions, models, refs, prompt and produced artifacts.
- Open the cloud-agent task on GitHub or open the generated Pull Request in GitHubble.
- Assign an existing Issue to `copilot-swe-agent[bot]` with agent assignment parameters.

## GitHub App requirements

GiHubble must have repository permission **Agent tasks: Read and write** for direct Agent Tasks API calls. Existing Issue assignment also depends on the repository permissions used by the Issues, Contents, Actions and Pull Requests APIs.

After changing GitHub App permissions, approve the requested permission update for the installation. Reconnect the account in GitHubble if GitHub continues returning HTTP 403.

## Availability limitations

The Agent Tasks REST API is in public preview and can change. GitHub currently documents the direct Start Task endpoint for Copilot Business and Copilot Enterprise users. Issue assignment availability is also controlled by the user's Copilot plan and repository/organization policy.

## Security

- Uses the existing GitHub App user access token stored through Android Keystore.
- Does not use a GitHub App installation token.
- Does not embed a GitHub Client Secret or private key.
- Sends prompts only to official `api.github.com` endpoints.
