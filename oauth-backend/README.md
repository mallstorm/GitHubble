# GitHubble OAuth backend

Минимальный trusted component для основного входа «Войти через GitHub».

## Route

Разверните `php/index.php` так, чтобы он был доступен как:

`POST https://YOUR_HOST/oauth/github/exchange`

## Environment

```
GITHUB_CLIENT_ID=...
GITHUB_CLIENT_SECRET=...
GITHUB_ALLOWED_REDIRECT_URI=githubble://oauth/callback
```

Client Secret хранится только на сервере. Не добавляйте его в Android APK, Git-репозиторий или клиентские настройки.

В настройках GitHub App добавьте Callback URL:

`githubble://oauth/callback`

При сборке Android можно передать адрес backend:

```
GITHUB_OAUTH_BACKEND=https://YOUR_HOST
```

Без backend основной OAuth web-flow не сможет выполнить code-to-token exchange; запасной Device Flow продолжает работать независимо.
