# GitHubble RC4.2.31 — перенос в новый приватный репозиторий

Источник: `mallstorm/GitHubble`, исходный коммит `2f4c9dc57736e7a9f2d904af3f3154f0bf1a0480`.
Этот архив представляет снимок исходного кода **без старой Git-истории**. Workflow адаптирован для автономного приватного репозитория.

## Загрузка из Termux (Android)

1. В другом аккаунте GitHub создайте **пустой Private** репозиторий `GitHubble`: **не** добавляйте README, License или .gitignore при создании.
2. Скачайте ZIP архива ветки `export/private-mirror-rc4.2.31` из исходного приватного репозитория в папку «Загрузки».
3. В Termux:

```sh
pkg install -y git gh unzip
termux-setup-storage
mkdir -p ~/githubble-mirror && cd ~/githubble-mirror
unzip -q ~/storage/downloads/<имя_скачанного_архива>.zip
cd GitHubble-*
git init -b main
git config user.name "YOUR_GITHUB_USERNAME"
git config user.email "YOUR_GITHUB_EMAIL"
git add -A
git commit -m "Import GitHubble RC4.2.31 portable source"
gh auth login -h github.com -p https -w
gh auth setup-git
git remote add origin https://github.com/OTHER_ACCOUNT/GitHubble.git
git push -u origin main
```

Вместо `OTHER_ACCOUNT` введите логин владельца нового репозитория. Не включайте в ZIP папки `.git` и ключи подписи; они отсутствуют в экспортируемом снимке.

## Автоматическая сборка

После первого `git push` запустится `GitHubble Android build (portable private mirror)` из `.github/workflows/android.yml`.

Также можно вручную: `Actions → GitHubble Android build (portable private mirror) → Run workflow → main`.

Workflow использует `ubuntu-latest`, Java 21, Gradle 9.5.1, Android SDK 36 и NDK 28.2.13676358.
Результат успешной сборки сохраняется в `Releases` **нового** репозитория. Не требуется указывать старый аккаунт.
Без дополнительных секретов будет доступен **debug APK** для установки и тестирования; unsigned Release APK не предназначен для установки.

## Если нужен производственный подписанный Release APK

GitHub Secrets исходного аккаунта **не копируются автоматически**. Если необходимо подписывать новые APK тем же сертификатом, отдельно и безопасно перенесите **исходный файл подписи и пароли**, не отправляя их в код или ZIP:

- `GITHUBBLE_RELEASE_KEYSTORE_BASE64` — PKCS#12 keystore в base64;
- `GITHUBBLE_RELEASE_STORE_PASSWORD`;
- `GITHUBBLE_RELEASE_KEY_PASSWORD`;
- `GITHUBBLE_RELEASE_KEY_ALIAS`.

Опционально: `GITHUB_CLIENT_ID` — публичный идентификатор собственного GitHub App; без него Gradle использует существующее значение по умолчанию. Для другого GitHub App также скорректируйте slug.

Добавляйте секреты только через `Settings → Secrets and variables → Actions`. Не публикуйте приватный ключ подписи. При смене ключа существующее Android-приложение нельзя будет обновить поверх старой установки, если подписи различаются.

## Ограничения и безопасность

- Сохраняйте новый репозиторий приватным: в проекте имеются интеграции OAuth/Subscription Proxy сторонних провайдеров и конфигурация, ещё не готовая к публичной публикации.
- Приватность не делает чужие OAuth-клиенты автоматически разрешёнными для использования: проверяйте условия правообладателей.
- Проверка полного содержимого Git-истории и лицензирования стороннего кода не заменяется этим снимком.
- Резервные ветки и история исходного репозитория **не переносятся**.
- Месячные минуты GitHub-hosted runners учитываются по владельцу репозитория. Соблюдайте условия GitHub в отношении нескольких личных аккаунтов.
- Не включайте выполнение недоверенного кода в workflow с ключами подписи.

## Изменения относительно исходного `main`

1. `.github/workflows/android.yml`: поддержка публикации в GitHub Releases по ручному `workflow_dispatch` на `main`, помимо `push`.
2. Добавлен этот файл с инструкцией переноса исходников и настройки GitHub Actions.
3. Исходная основная ветка `mallstorm/GitHubble:main` не менялась.
