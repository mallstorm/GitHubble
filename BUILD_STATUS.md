# Статус сборки GitHubble

Текущая версия: `1.0.0-rc3.2-repository-manager` (`versionCode 36`).

Рабочая кодовая база RC3.2 в `main` успешно прошла GitHub Actions workflow **Android RC CI** на коммите `b0d5a20a67b9e2dc90637387f6f9a4830509e3dc`.

Проверено в CI:

- Android SDK platform 36 / build-tools 36.0.0;
- Gradle-сборка RC-артефактов;
- генерация SBOM;
- публикация unsigned build artifact.

Позднейшие изменения в `main` после этого коммита относятся только к документации и не изменяют код приложения.

Release signing выполняется вне репозитория; keystore и пароли подписи в исходниках не хранятся.