---
name: ci-setup
description: Настроить CI для проекта: сборка, тесты, линтеры, кэш
---
1. Определи стек по файлам (gradle, package.json, pyproject, go.mod, Cargo.toml).
2. Создай .github/workflows/ci.yml: триггеры push и pull_request, кэш зависимостей, шаги build/test/lint, concurrency с отменой старых запусков, timeout-minutes.
3. Минимальные permissions: contents: read.
4. Закрепи версии actions по тегу мажорной версии.
5. Запусти и дождись зелёного результата; при падении примени навык ci-debug.
