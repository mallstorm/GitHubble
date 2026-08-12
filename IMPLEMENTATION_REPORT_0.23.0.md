# GitHubble 0.23.0 — GitHub Custom Tabs Flow

## Реализовано

1. Бесшовная регистрация GitHub:
   - кнопка «Создать аккаунт GitHub» в гостевом профиле;
   - официальная страница `https://github.com/signup` открывается через Android Custom Tab;
   - Chrome используется приоритетно, далее любой совместимый Custom Tabs-провайдер;
   - после возврата GitHubble предлагает продолжить подключение GiHubble.

2. Device Flow в оболочке GitHubble:
   - `verification_uri` открывается через тот же Custom Tab-модуль;
   - одноразовый код остаётся видимым и копируется в буфер;
   - опрос токена продолжается автоматически во время подтверждения;
   - после успешной авторизации загружается профиль.

3. Безопасность:
   - пароль, CAPTCHA, Google/Apple, 2FA и passkey обрабатываются только GitHub;
   - GitHubble не внедряет JavaScript и не читает содержимое страницы;
   - WebView не используется;
   - Client Secret и private key в APK отсутствуют.

4. Совместимость:
   - реализован dependency-free launcher стандартного Android Custom Tabs protocol;
   - fallback в обычный браузер предусмотрен только при отсутствии Custom Tabs-провайдера;
   - добавлены Android package visibility queries.
