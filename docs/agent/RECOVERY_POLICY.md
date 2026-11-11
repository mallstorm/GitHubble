# Политика восстановления сборок (`RetryPolicy`)

Лимиты по умолчанию: 8 запусков сборки, 5 автоисправлений, 3 повтора временных сбоев, 2 одинаковых ошибки подряд;
backoff 30 с … 15 мин (экспоненциально; серверный Retry-After приоритетнее).

- PERMISSION_ERROR, SIGNING_FAILURE, ANDROID_SDK_ERROR, BILLING_OR_QUOTA_BLOCK → WAIT_EXTERNAL (владелец; обход не делается).
- Временные (API_RATE_LIMIT, RUNNER_FAILURE, INFRASTRUCTURE_FAILURE, ARTIFACT_PUBLISHING_FAILURE) → RETRY_AFTER с backoff, затем WAIT_EXTERNAL.
- Исправимые в исходниках (компиляция, зависимости, Gradle, манифест, ресурсы, тесты, workflow) → FIX_SOURCE
  до лимита; повтор той же сигнатуры сверх лимита → FAIL.
- UNKNOWN → NEED_LOG_EXCERPT (агент должен запросить фрагмент лога), автоправка не разрешается.
Бюджет живёт в хосте на диалог и не сбрасывается моделью.
