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
MANUAL→REVIEW. **Статус:** политика реализована и протестирована. В `NativeAgentToolHost` она подключена как «защита в глубину»
для GitHub-записей: действие классифицируется хостом (`GitHubAgentOperations.action`), а режим выводится из текущего
уровня разрешений (`ExecutionMode.forLevel`); режим может только добавить требование подтверждения, но не снять его.
Тест `ExecutionModePolicyTest` фиксирует инвариант: прежняя политика уровней ни для одного инструмента не слабее политики режимов.
Отдельного переключателя AUTO/SEMI/MANUAL в UI нет — пользователь по-прежнему выбирает один из четырёх уровней разрешений.
