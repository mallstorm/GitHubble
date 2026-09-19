# Авторизация GiHubble

GitHubble поддерживает два способа входа.

## 1. Основной: «Войти через GitHub»

Это GitHub authorization-code flow с PKCE. Приложение открывает официальный GitHub, пользователь выбирает свой аккаунт и подтверждает GiHubble, после чего GitHub возвращает управление в Android через:

`githubble://oauth/callback`

В GitHub App **GiHubble** этот URI нужно добавить в **Callback URL**. Android-приложение не хранит Client Secret. Обмен authorization code на user access token выполняет HTTPS backend по маршруту:

`POST /oauth/github/exchange`

Готовый минимальный PHP endpoint находится в `oauth-backend/php/index.php`. На сервере задаются переменные окружения:

- `GITHUB_CLIENT_ID`;
- `GITHUB_CLIENT_SECRET`;
- `GITHUB_ALLOWED_REDIRECT_URI=githubble://oauth/callback`.

Адрес backend передаётся сборке через `GITHUB_OAUTH_BACKEND` или временно задаётся в Настройки → OAuth backend.

## 2. Запасной: Device Flow

Device Flow оставлен полностью рабочим. Пользователь нажимает **«Войти по 8-значному коду»**, GitHubble получает код GitHub, копирует его, открывает `github.com/login/device`, ждёт подтверждения и сохраняет токен в Android Keystore-backed хранилище.

Для Device Flow Client Secret не нужен. Используется публичный Client ID GiHubble.

## Безопасность

- пароль GitHub никогда не вводится в GitHubble;
- Client Secret не хранится в APK;
- основной web-flow защищён PKCE + `state`;
- callback принимается только через `githubble://oauth/callback`;
- backend должен работать только по HTTPS;
- OAuth-токены сохраняются в Android Keystore-backed SecureStore.

## Permissions for Account Center

Для отображения приватных репозиториев GitHub App должна быть установлена на нужные репозитории (или на все репозитории аккаунта) и иметь как минимум Metadata: read. Для просмотра и удаления GitHub Actions artifacts/cache нужны Actions: read/write. Для защищённого отката HEAD-коммита нужна Repository permission Contents: write. Для личного Billing Usage API требуется Account permission Plan: read.
