# GitHubble Smart Login

GitHubble uses the official GitHub OAuth Device Flow for installed clients.

## User experience

1. The user taps **Войти через GitHub**.
2. GitHubble requests a short-lived device code from GitHub.
3. The code is copied to the clipboard and the official `github.com/login/device` page opens.
4. GitHub presents the authentication methods available for that account: password, Google, Apple, passkey, 2FA, security key, or organization SSO.
5. GitHubble polls GitHub's token endpoint and stores the resulting token in Android Keystore-backed encrypted storage.

GitHubble never receives or stores the user's GitHub, Google, or Apple password.

## Build configuration

Register a GitHub App and enable **Device Flow**. Supply the public Client ID at build time:

```bash
GITHUB_CLIENT_ID=Ov23li... gradle --offline clean assembleRelease
```

or add the property outside source control:

```properties
GITHUB_CLIENT_ID=Ov23li...
```

No client secret or backend URL is required by the Android app for this flow.
