# Машина состояний задачи

Основной путь: CREATED → ANALYZING → PLANNING → APPROVAL → EXECUTING → TESTING → BUILDING → VERIFYING →
DELIVERING → COMPLETED. Возвраты: APPROVAL→PLANNING; TESTING/BUILDING/VERIFYING→EXECUTING (исправление);
VERIFYING→BUILDING; DELIVERING→VERIFYING.
Из любого активного состояния: PAUSED, WAITING_PERMISSION, WAITING_EXTERNAL, RECOVERING, FAILED, CANCELLED.
`TaskJournal` возвращает задачу строго в прерванное состояние. ROLLED_BACK — из EXECUTING…DELIVERING и FAILED.
Терминальные: COMPLETED, CANCELLED, ROLLED_BACK. Недопустимый переход бросает исключение (тесты `TaskJournalTest`).

Завершение без CI: ANALYZING, EXECUTING и TESTING могут перейти в COMPLETED напрямую (чистый ответ или локальные правки);
BUILDING и VERIFYING — нет.

**Статус:** модель реализована, протестирована и подключена к запускам агента в чате через `AgentRunJournal`
(см. `docs/architecture/AGENT_CORE.md`). Возобновление прерванной задачи после перезапуска не реализовано:
такие журналы помечаются FAILED с причиной.
