# GitHubble OAuth — Device Flow

GitHubble использует только официальный GitHub OAuth Device Flow. Пароль GitHub
никогда не вводится в приложение и не хранится локально.

1. В настройках GitHub App включите **Device Flow**.
2. Передайте публичный Client ID через `GITHUB_CLIENT_ID` в GitHub Actions secret
   или Gradle property. Client secret мобильному приложению не нужен.
3. Пользователь получает одноразовый код и подтверждает вход на официальной
   странице `https://github.com/login/device`.
4. Access token хранится через Android Keystore (AES-GCM). Если GitHub возвращает
   срок действия и refresh token, их сроки сохраняются вместе с сессией.
5. При HTTP 401 приложение помечает сессию как требующую повторного входа и не
   запускает бесконечные повторы.

PKCE/backend-flow из старых RC-сборок удалён и не является частью production UI.
