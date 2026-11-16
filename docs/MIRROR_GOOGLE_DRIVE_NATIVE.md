# Native Google Drive integration in GitHubble Mirror Manager

## User flow

Settings → Account and GitHub → Mirror setup → Google Drive → **Sign in with Google**.
Google Identity Services on Android displays an account selector/consent prompt, then returns a short-lived OAuth access token. GitHubble creates **My Drive/GitHubble/mirror-registry.json** automatically through Google Drive REST API v3. The file is visible in Google Drive. Unlike the older Android SAF mode, no manual JSON picker is required.

The Google account is independent from all connected GitHub accounts. GitHub account A/B OAuth sessions are NEVER used as Google credentials, and signing into OpenAI with Google does not grant GitHubble its own Google Drive authorization.

## OAuth prerequisites — required before first real sign in

1. Open Google Cloud Console and create/select a Google Cloud project.
2. Enable Google Drive API in that Google Cloud project.
3. Configure the Google Auth Platform OAuth consent screen (app name GitHubble, test users where applicable). Include `https://www.googleapis.com/auth/drive.file` and `https://www.googleapis.com/auth/userinfo.email`. The Drive scope is non-sensitive and lets the app access only app-created or user-authorized files.
4. Register an **Android OAuth client** with the exact installed Android package name and **SHA-1 of the actual APK signing certificate**. For CI Debug APKs: `app.githubble.mobile.debug`. For signed release APKs: `app.githubble.mobile`. Both can require separate OAuth clients. The CI runner's disposable debug signing key can change between builds, so a **stable signing key** is required for reliable sign in. Register the corresponding certificate SHA-1. No client secret or Google service-account key goes inside the APK.
5. Publish/authorize consent and testing users as appropriate. If OAuth API returns 403, verify Google Drive API enabled, correct Android OAuth client, consent mode and signing SHA-1. The user must consent once per app authorization.

## Google account safety

- `AuthorizationClient.authorize()` requests Drive file scope and userinfo email scope; account chooser is forced on user-initiated Connect/Switch. A background worker uses the previously selected Google account.
- Get `/oauth2/v3/userinfo` and compare stable `sub` for every synchronization to prevent uploading to the wrong Drive account. Store only `sub` and email, Drive folder/file IDs, last-sync metadata — never access/refresh tokens.
- Google Play Services supplies short-lived tokens and may require fresh consent. The worker never opens prompts; reconnect in Settings if needed.
- Disconnect removes only local cloud connection metadata and cancels the worker. It does not delete the user's Google Drive folder or revoke unrelated app grants.

## Registry synchronization

- Auto-created visible folder `GitHubble`, file `mirror-registry.json` with `{format:'githubble-mirror-links',schema:1,links:[...]}`; links contain project names and pair identifiers but NO GitHub secrets or repository source.
- The same validated union-based merge as `MirrorCloudStore` applies to native Google Drive. A corrupt remote file is never silently overwritten.
- Periodic WorkManager sync (~1 hour), one-time sync after creating/recovering pairs, and manual sync in Settings. Requires connectivity and Play Services authorization.
- There is no atomic cross-device transaction for the generic JSON file. Two simultaneous devices may require an additional resync to converge.
- **Only linked-pair metadata** currently syncs; entire GitHubble app settings, chat histories, GitHub token stores, and local OneShot history are not backed up by this version.

## Legacy cloud modes

The older file-picker-based Android SAF connection remains available under 'Other clouds' for OneDrive, Yandex Disk, compatible DocumentsProviders and manual Google Drive JSON selection. Native Google authorization is now the primary Google Drive route.
