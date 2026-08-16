# GitHubble 1.0.0 RC3.2 — Repository Manager

Текущая линия: `1.0.0-rc3.2-repository-manager`, versionCode `36`.

Основные изменения RC3.2:

- полноценное управление репозиториями из Android-приложения;
- создание публичных и приватных репозиториев;
- изменение имени и описания;
- переключение `Public ↔ Private` с подтверждением;
- безопасное удаление после ввода точного `owner/repository`;
- импорт Android-папки через Storage Access Framework с сохранением структуры;
- импорт ZIP как исходников;
- загрузка одиночного файла или ZIP как файла;
- загрузка через Git blobs/trees/commits без удержания больших файлов целиком в памяти;
- защита от небезопасных ZIP-путей и служебных `.git`/OS-файлов;
- поддержка пустых репозиториев через безопасный bootstrap;
- обновлённые Repository Contents / Administration / Workflows permissions;
- сохранены Device Flow, Keystore-backed токены, расширенный поиск, трекинг, Issues/PR/Discussions, офлайн-функции и Copilot Cloud Agent.

Кодовая база RC3.2 успешно прошла workflow **Android RC CI**. Release signing выполняется вне исходного репозитория.