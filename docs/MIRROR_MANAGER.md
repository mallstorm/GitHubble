# GitHubble Mirror Manager — независимое Git-зеркало (этап II)

Ветка: feat/mirror-manager. Контроллер: приватный репозиторий mallstorm/GitHubble-Mirror-Control, создаваемый **по явному нажатию** в GitHubble. Fork не используется.

## Реализованный поток

1. Выбор исходного собственного репозитория и другого подключённого аккаунта назначения.
2. По отдельному подтверждению создаётся **новый пустой независимый** репозиторий с той же приватностью, что и у источника (auto_init=false, fork=false). Если репозиторий назначения уже существует, создание запрещено.
3. GitHubble инициирует workflow_dispatch в **приватном контроллере**. Через inputs передаются только имена репозиториев, режим операции, идентификатор и SHA-256 контрольного набора refs. Никогда не передаётся пользовательский OAuth-токен.
4. Доверенный runner получает приватный ключ GitHub App из **Actions Environment Secret**, кратковременно выпускает два scoped installation access token: source только Contents:read; target Contents:write + Workflows:write.
5. На runner выполняются bare Git clone, fetch всех heads/tags, git lfs fetch/push --all, затем force/prune push только refs/heads/* и refs/tags/*. GitHub-внутренние refs/pull/* не переносимы. Репозиторий не скачивается на устройство.
6. Для разовой копии runner требует пустой target. Для управляемого зеркала runner сравнивает его SHA-256 fingerprint с ранее зафиксированным; при независимых изменениях операция **останавливается без перезаписи**.
7. Mirror Manager отслеживает запуск через приватный Actions API, проверяет совпадение исходных и целевых refs по SHA, сохраняет baseline, выравнивает default branch.
8. Ручное обновление доступно лишь для проверенного управляемого зеркала и всегда требует дополнительного подтверждения.

## Разовая настройка безопасного контроллера

1. Аккаунт @mallstorm должен быть подключён к GitHubble. В разделе Mirror Manager нажать «Создать / обновить контроллер». Приложение создаст **приватный** репозиторий GitHubble-Mirror-Control и поместит в него единственный доверенный workflow. Не используйте публичный контроллер.
2. Настройте GitHub App «GiHubble» в Developer settings → GitHub Apps. Для источника нужна Contents:read; для назначения Contents:write и Workflows:write. Права App могут быть шире, но runner выдаёт минимальные разрешения каждому токену. Для создания репозитория пользователем нужен Repository creation:write (или Administration:write по старым правилам). Для вызова контроллера нужен Actions:write.
3. Установите GitHub App на оба аккаунта (mallstorm и Digger401). Доступ на **все репозитории** проще для только что создаваемых зеркал; при «Selected repositories» потребуется явно включить новый target.
4. На GitHub: GitHubble-Mirror-Control → Settings → Environments → создайте \`mirror-production\`. Рекомендуется настроить Required reviewers. В разделе Environment secrets добавьте \`GITHUBBLE_MIRROR_APP_PRIVATE_KEY\` (приватный ключ GitHub App, созданный только через GitHub; не вставляйте его в чат, в приложение или коммиты).
5. В том же Environment variables добавьте \`GITHUBBLE_MIRROR_APP_CLIENT_ID\` (публичный Client ID зарегистрированной GitHub App, строка \`Iv...\`).
6. Убедитесь, что workflow \`.github/workflows/githubble-mirror-controller.yml\` активен на default branch, а Actions разрешены в приватном репозитории. После этого можно инициировать зеркало из приложения.

**Важное ограничение:** пока GitHub App key не настроен в Actions Environment Secrets, нельзя считать облачное зеркалирование активным. Программа может создать пустой target, но runner завершится ошибкой полномочий. Такой target не удаляется автоматически, а повторный запуск безопасен только при отсутствии refs.

## Безопасность

- Нет общей OAuth-сессии, токена в APK/SharedPreferences или логах. Credentials получаются независимо на каждый запуск и автоматически отзываются action.
- Доступ к приватным данным ограничен приватным контроллером. На public GitHubble никакие секреты или имена приватных mirror operations не публикуются.
- Controller workflow имеет \`workflow_dispatch\` и проверяет immutable GitHub actor ID владельца mallstorm.
- Input values проверяются по regex, никакая строка пользователя не вставляется прямо в исполняемый shell-код.
- Любая ошибка App token, branch protection, rulesets, Git LFS или несовпадение SHA завершает операцию без утверждения успеха.
- Force/prune меняет только целевые refs; исходный Git репозиторий не модифицируется.
- Git LFS конфигурация \`.lfsconfig\` блокируется до индивидуальной проверки, чтобы не передавать credential на произвольный LFS host.
- Исполнение файлов или скриптов из исходного репозитория на runner не производится.

## Не входит в Git-зеркало

Issues/PR/Discussions, GitHub Releases assets/APK, Wiki, GitHub Actions secrets и журналы workflow требуют отдельных механизмов. GitHub Actions secrets из исходного репозитория не экспортируются и не копируются. Автоматическая серверная синхронизация по расписанию также пока не реализована: поддержаны ручные подтверждённые обновления.

## Проверка

CI собирает Debug APK, запускает JVM-тесты безопасности и проверяет lintRelease. End-to-end перенос между mallstorm и Digger401 возможен только после пользовательской установки GitHub App permissions и её приватного ключа в контроллере. Работа с реальными исходными проектами запускается из приложения **только по явному подтверждению**.
