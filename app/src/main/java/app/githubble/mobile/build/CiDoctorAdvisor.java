package app.githubble.mobile.build;

import app.githubble.mobile.agent.core.FailureClass;

/**
 * Read-only CI Doctor guidance. Classification and secret-redacted logs are supplied by
 * the existing AgentBuildDiagnostics pipeline; this class never initiates an Actions run,
 * changes repository files or sends diagnostic data to an AI provider.
 */
public final class CiDoctorAdvisor {
    private CiDoctorAdvisor() {}

    public static final class Advice {
        public final String title;
        public final String reason;
        public final String nextStep;
        public final boolean sourceFixable;
        public final boolean transientFailure;

        private Advice(String title, String reason, String nextStep, FailureClass kind) {
            this.title = title;
            this.reason = reason;
            this.nextStep = nextStep;
            this.sourceFixable = kind.sourceFixable;
            this.transientFailure = kind.transientFailure;
        }
    }

    /** Unknown or newly introduced failure classes must fail closed. */
    public static Advice explain(String code) {
        FailureClass kind;
        try {
            kind = FailureClass.valueOf(code == null ? "" : code);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            kind = FailureClass.UNKNOWN;
        }
        switch (kind) {
            case BILLING_OR_QUOTA_BLOCK:
                return new Advice("Лимит или оплата GitHub", "GitHub заблокировал выполнение из-за бюджета, квоты или платежей.",
                        "Проверьте Actions usage и Billing в настройках владельца. Правка исходников не поможет.", kind);
            case API_RATE_LIMIT:
                return new Advice("Лимит запросов GitHub API", "Превышен лимит API или временное ограничение доступа.",
                        "Проверьте rate limit и повторите запуск вручную после снятия ограничения.", kind);
            case RUNNER_FAILURE:
                return new Advice("Сбой GitHub Runner", "Исполнитель был остановлен или не смог завершить задание.",
                        "Посмотрите состояние GitHub Actions и повторите сборку вручную, если инфраструктура исправна.", kind);
            case INFRASTRUCTURE_FAILURE:
                return new Advice("Сбой сети или инфраструктуры", "Соединение, сервис или удалённая загрузка временно недоступны.",
                        "Проверьте статус сервисов и сеть. Затем повторите запуск вручную.", kind);
            case PERMISSION_ERROR:
                return new Advice("Недостаточно прав", "Токену, GitHub App или GITHUB_TOKEN не хватает необходимых разрешений.",
                        "Проверьте permissions workflow, доступ GitHub App и владельца репозитория без вывода токенов в журнал.", kind);
            case SIGNING_FAILURE:
                return new Advice("Ошибка подписи", "Не удалось прочитать ключ или подписать артефакт.",
                        "Проверьте signingConfig, секреты Actions и наличие корректного keystore. Не загружайте ключи в чат.", kind);
            case ARTIFACT_PUBLISHING_FAILURE:
                return new Advice("Не опубликован артефакт", "Сборка могла завершиться, но публикация APK/ZIP не удалась.",
                        "Проверьте upload-artifact, квоты хранилища и права actions:write. Затем повторите публикацию.", kind);
            case WORKFLOW_CONFIGURATION_ERROR:
                return new Advice("Ошибка workflow YAML", "GitHub не смог корректно интерпретировать workflow или action.",
                        "Проверьте .github/workflows, имена actions, версии и синтаксис. Исправление внесите отдельным PR.", kind);
            case ANDROID_SDK_ERROR:
                return new Advice("Проблема Android SDK/NDK", "Не найдена или несовместима часть Android toolchain.",
                        "Сверьте compileSdk, NDK, версии build-tools и шаги установки SDK в Actions.", kind);
            case MANIFEST_ERROR:
                return new Advice("Конфликт AndroidManifest", "Слияние манифестов или атрибутов Android завершилось ошибкой.",
                        "Найдите конфликтующий атрибут/permission в логе manifest merger и подготовьте правку через PR.", kind);
            case RESOURCE_LINKING_ERROR:
                return new Advice("Не удалось собрать Android-ресурсы", "AAPT/AAPT2 не смог разрешить ресурсы.",
                        "Проверьте имена ресурсов, ссылки в XML и версии зависимостей. Правки — через PR.", kind);
            case DEPENDENCY_RESOLUTION_ERROR:
                return new Advice("Не удалось загрузить зависимости", "Gradle не нашёл совместимую библиотеку или репозиторий.",
                        "Проверьте координаты, версии, repositories и доступность Maven. Не меняйте версии наугад.", kind);
            case GRADLE_CONFIGURATION_ERROR:
                return new Advice("Ошибка конфигурации Gradle", "Плагин, Gradle или настройки проекта несовместимы.",
                        "Проверьте wrapper, AGP/JDK и настройки build.gradle. Подготовьте адресный PR.", kind);
            case TEST_FAILURE:
                return new Advice("Не прошли тесты", "Один или несколько тестов завершились ошибкой.",
                        "Откройте имя теста и assertion в журнале; исправьте тест или логику через PR.", kind);
            case KOTLIN_JAVA_ERROR:
            case COMPILATION_ERROR:
                return new Advice("Ошибка компиляции исходников", "Компилятор нашёл ошибку Java/Kotlin или другой компиляции.",
                        "Найдите первую значимую ошибку с файлом и строкой в журнале. Исправление — через PR.", kind);
            default:
                return new Advice("Причина не определена", "Недостаточно подтверждённых признаков для классификации.",
                        "Проверьте первый упавший шаг и полный журнал GitHub. Не изменяйте проект автоматически.", kind);
        }
    }
}
