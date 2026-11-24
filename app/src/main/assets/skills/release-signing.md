---
name: release-signing
description: Создать ключ подписи Android и записать его в секреты Actions репозитория
---
1. repository_files и repository_read_file: найди app/build.gradle(.kts) и .github/workflows; пойми, как собирается release и подписывается ли он уже.
2. Если секретов подписи нет — вызови setup_release_signing {repository, account?}. Хост сам сгенерирует ключ на телефоне (PKCS12, RSA 2048, 30 лет), покажет пользователю карточку и запишет секреты ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD. Значения тебе недоступны — не проси и не выдумывай их.
3. Если ответ already_exists — не заменяй без явного согласия (ask_user): старые подписанные приложения перестанут обновляться.
4. Подключи подписанную сборку: в workflow декодируй ANDROID_KEYSTORE_BASE64 в файл (base64 -d), передай пароли и alias через env/gradle-свойства, в signingConfigs.release прочитай их из System.getenv. Файл ключа в репозиторий не коммить.
5. Запусти сборку (dispatch_workflow), дождись (await_build), проверь артефакт release.apk (build_artifacts). Сообщи отпечаток сертификата SHA-256 из ответа.
