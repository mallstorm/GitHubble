---
name: dependabot-setup
description: Включить автообновление зависимостей (Dependabot) и проверки безопасности
---
1. Определи экосистемы (gradle, npm, pip, github-actions и др.).
2. Создай .github/dependabot.yml: недельное расписание, группировка minor/patch, лимит открытых PR.
3. github_get /repos/{o}/{r}: проверь, включены ли alerts уязвимостей; предложи включить через github_request (хост спросит).
4. Объясни, как Dependabot PR проходит через CI и как их сливать.
