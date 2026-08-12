# Статус сборки Stage 25–36

## Выполнено

- структура Android-проекта обновлена;
- версия повышена до `0.3.0-alpha03`;
- добавлены Room, WorkManager и kapt-зависимости;
- исходники этапов 25–36 интегрированы;
- выполнены статические проверки файлов и ресурсов.

## APK

APK в текущем окружении не создан, поскольку отсутствуют:

- Android SDK Platform 36;
- Android Build Tools 36.0.0;
- Gradle/Gradle Wrapper с необходимыми дистрибутивами.

Команда в подготовленной Android-среде:

```bash
./gradlew clean test assembleDebug
```

Ожидаемый артефакт:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Перед сборкой нужно указать `GITHUB_CLIENT_ID` и URL OAuth backend.
