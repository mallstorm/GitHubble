---
name: security-hardening
description: Усилить безопасность репозитория: проверки, права, секреты
tools: github_request
---
1. github_get: настройки безопасности, права workflow по умолчанию, разрешённые actions.
2. Проверь workflow: минимальные permissions, закреплённые версии actions, отсутствие pull_request_target с чужим кодом.
3. Предложи: secret scanning, Dependabot alerts, защиту основной ветки, запрет force-push.
4. Применяй по одному пункту через github_request; каждое изменение подтверждает хост.
