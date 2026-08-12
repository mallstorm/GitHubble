# GitHub App client configuration

Registered integration: `GiHubble`

The public GitHub App Client ID is injected during build through `GITHUB_CLIENT_ID`.
No client secret, private key, access token, or webhook secret is stored in the APK or source archive.

Authentication uses GitHub Device Flow. The user completes authentication on the official GitHub page using any method available to the account: password, Google, Apple, passkey, 2FA, security key, or SSO.
