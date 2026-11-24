---
name: docker-ci
description: Dockerfile и публикация образа в GHCR через Actions
---
1. Изучи приложение: язык, порт, переменные окружения.
2. Напиши многоэтапный Dockerfile минимального размера, без секретов в образе, с непривилегированным пользователем.
3. Создай workflow: login-action в ghcr.io с GITHUB_TOKEN, build-push-action, теги по версии и sha.
4. Проверь сборку и опиши запуск образа командой.
