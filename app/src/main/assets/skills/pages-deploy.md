---
name: pages-deploy
description: Опубликовать сайт на GitHub Pages через Actions
---
1. Определи тип сайта: статический HTML или сборка (Vite, Jekyll, Docusaurus).
2. Создай .github/workflows/pages.yml: configure-pages, upload-pages-artifact, deploy-pages с нужными permissions.
3. Включи Pages с источником GitHub Actions через github_request (хост спросит).
4. Запусти деплой и проверь адрес сайта.
