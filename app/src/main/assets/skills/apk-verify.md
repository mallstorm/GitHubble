---
name: apk-verify
description: Проверить собранный APK и артефакты сборки
tools: build_artifacts, verify_build
---
1. build_artifacts: найди APK последней успешной сборки.
2. verify_build и artifact_verdict: коммит, подпись, версия, размер.
3. Сравни versionName/versionCode с ожидаемыми.
4. Сообщи вердикт одной строкой и список расхождений.
