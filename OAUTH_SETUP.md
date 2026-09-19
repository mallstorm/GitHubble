# Авторизация GiHubble

GitHubble использует официальный GitHub Device Flow зарегистрированной GitHub App `GiHubble`.

1. В GitHub App включите **Enable Device Flow**.
2. Настройте необходимые Repository, Organization и Account permissions по принципу минимальных прав.
3. Перед сборкой передайте публичный Client ID через `GITHUB_CLIENT_ID`.
4. Не добавляйте в Android-проект Client Secret, private key, webhook secret или пользовательский access token.

Пользователь получает одноразовый код, подтверждает вход на официальной странице GitHub, после чего токен сохраняется в Android Keystore-backed зашифрованном хранилище. Callback URI и собственный OAuth backend для текущего Device Flow не требуются.


## Permissions for Account Center

Для отображения приватных репозиториев GitHub App должна быть установлена на нужные репозитории (или на все репозитории аккаунта) и иметь как минимум Metadata: read. Для просмотра и удаления GitHub Actions artifacts/cache нужны Actions: read/write. Для защищённого отката HEAD-коммита нужна Repository permission Contents: write. Для личного Billing Usage API требуется Account permission Plan: read.

Способ оплаты не передаётся приложению: управление картой/PayPal выполняется только на официальной странице GitHub Billing, открываемой из GitHubble через защищённую Custom Tab.
