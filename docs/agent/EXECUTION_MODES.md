# Режимы выполнения

`ExecutionMode.decide(action, stageApproved, releasePreApproved)`:

| Действие | AUTO | SEMI | MANUAL |
|---|---|---|---|
| READ | allow | allow | allow |
| LOCAL_WRITE, WORKING_BRANCH_WRITE, BUILD | allow | allow | approval |
| PUBLISH | allow | approval | approval |
| MERGE_PROTECTED | allow | allow только при предварительном одобрении релиза | approval |
| DANGEROUS | approval | approval | approval |

DANGEROUS (удаление репозитория, секреты/права/биллинг, отключение проверок, прямая запись в защищённую
ветку) всегда требует отдельного подтверждения. Соответствие уровням: AUTO→GITHUB_AUTO, SEMI→LOCAL_AUTO,
MANUAL→REVIEW. **Статус:** политика реализована и протестирована; переключателя режима в UI и подключения
к `NativeAgentToolHost` пока нет — действуют прежние четыре уровня разрешений.
