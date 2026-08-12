# GitHubble 0.13 security migration

- Компрометированный тестовый keystore удалён из исходников.
- Пароли подписи удалены из Gradle.
- Release собирается unsigned и подписывается внешним временным ключом только для теста.
- GitHub access token и PKCE verifier хранятся через Android Keystore AES/GCM.
- OAuth использует state, срок жизни 10 минут и PKCE S256.
- Client secret не включается в APK.
