# Машина состояний задачи

Основной путь: CREATED → ANALYZING → PLANNING → APPROVAL → EXECUTING → TESTING → BUILDING → VERIFYING →
DELIVERING → COMPLETED. Возвраты: APPROVAL→PLANNING; TESTING/BUILDING/VERIFYING→EXECUTING (исправление);
VERIFYING→BUILDING; DELIVERING→VERIFYING.
Из любого активного состояния: PAUSED, WAITING_PERMISSION, WAITING_EXTERNAL, RECOVERING, FAILED, CANCELLED.
`TaskJournal` возвращает задачу строго в прерванное состояние. ROLLED_BACK — из EXECUTING…DELIVERING и FAILED.
Терминальные: COMPLETED, CANCELLED, ROLLED_BACK. Недопустимый переход бросает исключение (тесты `TaskJournalTest`).

**Статус:** модель реализована и протестирована, но в чат не подключена.
