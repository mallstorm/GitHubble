---
name: actions-optimizer
description: Ускорить и удешевить GitHub Actions: кэш, concurrency, лишние запуски
---
1. github_get /repos/{o}/{r}/actions/workflows и recent_build_runs: время и частота запусков.
2. Найди узкие места: нет кэша зависимостей, избыточная матрица, запуск на каждый коммит ветки, долгие шаги.
3. Предложи правки: кэш gradle/npm/pip, concurrency cancel-in-progress, paths-фильтры, timeout, меньше матрица.
4. Внеси правки через ChangeSet после согласия; оцени экономию минут.
5. Для старых кэшей и артефактов предложи навык actions-maintenance.
