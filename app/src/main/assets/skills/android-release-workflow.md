---
name: android-release-workflow
description: Workflow GitHub Actions для подписанной release-сборки Android с публикацией релиза
---
1. Прочитай структуру проекта: модули Gradle, версии JDK/AGP, наличие wrapper, существующие workflow.
2. Если нет секретов подписи — сначала выполни навык release-signing.
3. Создай .github/workflows/release.yml: checkout, setup-java 17, gradle cache, декодирование keystore из секретов, ./gradlew assembleRelease (или bundleRelease), загрузка артефакта, при теге v* — create GitHub Release с APK.
4. Версию (versionName/versionCode) бери из файла проекта или тега, не хардкодь в workflow.
5. Предложи изменения через ChangeSet, дождись принятия, запусти сборку и проверь результат через verify_build.
