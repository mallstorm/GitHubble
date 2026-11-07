package app.githubble.mobile.ui;

import android.app.Activity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import app.githubble.mobile.R;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Account access pages composed from the same settings rows, headers and palette as the app. */
public final class AccountAccessScreen {
  public interface Actions {
    void blocks();

    void limits();

    void permissions();

    void authorize();

    void refresh();

    void installation(long id);
  }

  private final Activity activity;
  private final LinearLayout host;
  private final HomeScreen.Palette palette;
  private final Actions actions;
  private LinearLayout installations;

  public AccountAccessScreen(
      Activity activity, LinearLayout host, HomeScreen.Palette palette, Actions actions) {
    this.activity = activity;
    this.host = host;
    this.palette = palette;
    this.actions = actions;
  }

  private ImageView icon(int resource) {
    ImageView image = new ImageView(activity);
    image.setImageResource(resource);
    image.setColorFilter(palette.green);
    int padding = (int) (10 * activity.getResources().getDisplayMetrics().density + .5f);
    image.setPadding(padding, padding, padding, padding);
    return image;
  }

  private void render(
      LinearLayout parent, SettingsScreen.Header header, List<SettingsScreen.Section> sections) {
    new SettingsScreen(activity, parent, palette).renderPage(header, palette.green, sections);
  }

  public void overview(String login) {
    List<SettingsScreen.Section> sections = new ArrayList<>();
    SettingsScreen.Section personal = new SettingsScreen.Section("Управление");
    personal.add(
        SettingsScreen.Item.action(
            R.drawable.ic_profile,
            "Заблокированные пользователи",
            "Список блокировок и разблокировка",
            "",
            actions::blocks));
    personal.add(
        SettingsScreen.Item.action(
            R.drawable.ic_lock,
            "Ограничения взаимодействия",
            "Кто может создавать задачи, PR и комментарии",
            "",
            actions::limits));
    sections.add(personal);
    SettingsScreen.Section connection = new SettingsScreen.Section("Подключение GitHub");
    connection.add(
        SettingsScreen.Item.action(
            R.drawable.ic_github_mark,
            "Разрешения GiHubble",
            "Проверить и подтвердить доступ на GitHub",
            "",
            actions::permissions));
    connection.add(
        SettingsScreen.Item.action(
            R.drawable.ic_agent_link,
            "Обновить авторизацию",
            "Повторно войти для применения новых прав",
            "",
            actions::authorize));
    sections.add(connection);
    render(
        host,
        new SettingsScreen.Header(
            icon(R.drawable.ic_profile),
            "Управление аккаунтом",
            login.isEmpty() ? "GitHub" : "@" + login,
            null),
        sections);
    installations = new LinearLayout(activity);
    installations.setOrientation(LinearLayout.VERTICAL);
    host.addView(installations);
    render(
        installations,
        null,
        Collections.singletonList(
            new SettingsScreen.Section("Доступные установки")
                .note("Загружаем сведения о доступе…")));
  }

  public void installations(JSONArray items) {
    installations.removeAllViews();
    SettingsScreen.Section section =
        new SettingsScreen.Section("Доступные установки · " + items.length());
    section.note(
        "Установки GiHubble, доступные этому аккаунту. Откройте установку, чтобы посмотреть её"
            + " права.");
    if (items.length() == 0) section.note("Установок пока нет. Подключите GiHubble на GitHub.");
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.optJSONObject(i);
      if (item == null) continue;
      JSONObject owner = item.optJSONObject("account");
      String login = owner == null ? "GitHub" : owner.optString("login", "GitHub");
      long id = item.optLong("id");
      boolean org =
          owner != null
              && "Organization".equals(owner.optString("type", item.optString("target_type")));
      JSONObject rights = item.optJSONObject("permissions");
      int count = rights == null ? 0 : rights.length();
      String subtitle =
          (org ? "Организация" : "Личный аккаунт")
              + " · "
              + selection(item)
              + (item.isNull("suspended_at") ? "" : " · Приостановлена");
      SettingsScreen.Item row =
          SettingsScreen.Item.action(
              org ? R.drawable.ic_organization : R.drawable.ic_repository,
              "@" + login,
              subtitle,
              "",
              () -> actions.installation(id));
      row.badge("Права: " + count);
      section.add(row);
    }
    section.add(
        SettingsScreen.Item.action(
            R.drawable.ic_history,
            "Обновить список",
            "Запросить актуальные сведения у GitHub",
            "",
            actions::refresh));
    render(installations, null, Collections.singletonList(section));
  }

  public void error(String message) {
    installations.removeAllViews();
    SettingsScreen.Section state = new SettingsScreen.Section("Доступные установки");
    state.note(message == null ? "Не удалось загрузить сведения" : message);
    state.add(
        SettingsScreen.Item.action(
            R.drawable.ic_history, "Повторить загрузку", "", "", actions::refresh));
    render(installations, null, Collections.singletonList(state));
  }

  private static String selection(JSONObject item) {
    return "all".equals(item.optString("repository_selection"))
        ? "Все репозитории"
        : "Выбранные репозитории";
  }

  public void detail(JSONObject item) {
    JSONObject owner = item.optJSONObject("account");
    String login = owner == null ? "GitHub" : owner.optString("login", "GitHub");
    List<SettingsScreen.Section> sections = new ArrayList<>();
    SettingsScreen.Section info = new SettingsScreen.Section("Доступ к репозиториям");
    info.add(
        SettingsScreen.Item.info(R.drawable.ic_repository, "Репозитории", selection(item), ""));
    info.add(
        SettingsScreen.Item.info(
            R.drawable.ic_lock,
            "Статус установки",
            item.isNull("suspended_at") ? "Активна" : "Приостановлена",
            ""));
    info.note(
        "Показаны права этой установки. Личные операции также зависят от пользовательской"
            + " авторизации и вашей роли на GitHub.");
    sections.add(info);
    JSONObject permissions = item.optJSONObject("permissions");
    Map<String, String> sorted = new TreeMap<>();
    if (permissions != null)
      for (java.util.Iterator<String> it = permissions.keys(); it.hasNext(); ) {
        String key = it.next();
        sorted.put(key, permissions.optString(key));
      }
    Map<String, SettingsScreen.Section> groups = new java.util.LinkedHashMap<>();
    for (String name :
        new String[] {
          "Репозиторий",
          "Совместная работа",
          "Автоматизация и публикация",
          "Безопасность",
          "Дополнительный доступ"
        }) groups.put(name, new SettingsScreen.Section(name));
    if (sorted.isEmpty())
      sections.add(
          new SettingsScreen.Section("Согласованные права")
              .note("GitHub не передал сведения о правах."));
    for (Map.Entry<String, String> entry : sorted.entrySet())
      groups
          .get(group(entry.getKey()))
          .add(
              SettingsScreen.Item.info(
                  R.drawable.ic_lock, title(entry.getKey()), level(entry.getValue()), ""));
    for (SettingsScreen.Section section : groups.values())
      if (!section.entries.isEmpty()) sections.add(section);
    SettingsScreen.Section manage = new SettingsScreen.Section("Настройка доступа");
    manage.add(
        SettingsScreen.Item.action(
            R.drawable.ic_github_mark,
            "Открыть настройки GitHub",
            "Изменить доступ установки",
            "",
            actions::permissions));
    sections.add(manage);
    render(
        host,
        new SettingsScreen.Header(
            icon(R.drawable.ic_github_mark), "@" + login, "Разрешения GiHubble", null),
        sections);
  }

  private static String group(String key) {
    switch (key) {
      case "contents":
      case "metadata":
      case "administration":
      case "repository_hooks":
      case "repository_projects":
      case "repository_custom_properties":
        return "Репозиторий";
      case "issues":
      case "pull_requests":
      case "discussions":
      case "members":
      case "organization_projects":
      case "organization_administration":
        return "Совместная работа";
      case "actions":
      case "actions_variables":
      case "secrets":
      case "workflows":
      case "checks":
      case "statuses":
      case "deployments":
      case "environments":
      case "pages":
      case "packages":
      case "agent_tasks":
      case "organization_actions_variables":
      case "organization_secrets":
        return "Автоматизация и публикация";
      case "security_events":
      case "vulnerability_alerts":
      case "repository_advisories":
        return "Безопасность";
      default:
        return "Дополнительный доступ";
    }
  }

  public static String level(String value) {
    if ("write".equals(value)) return "Чтение и изменение";
    if ("read".equals(value)) return "Только чтение";
    if ("none".equals(value)) return "Нет доступа";
    return "Уровень не определён";
  }

  public static String title(String key) {
    switch (key) {
      case "contents":
        return "Файлы, коммиты и релизы";
      case "metadata":
        return "Сведения о репозитории";
      case "administration":
        return "Управление репозиторием";
      case "issues":
        return "Задачи и комментарии";
      case "pull_requests":
        return "Pull Requests и ревью";
      case "actions":
        return "Запуски GitHub Actions";
      case "actions_variables":
        return "Переменные Actions";
      case "secrets":
        return "Секреты Actions";
      case "workflows":
        return "Файлы рабочих процессов";
      case "checks":
        return "Проверки кода";
      case "statuses":
        return "Статусы коммитов";
      case "deployments":
        return "Развёртывания";
      case "environments":
        return "Окружения";
      case "discussions":
        return "Обсуждения";
      case "pages":
        return "GitHub Pages";
      case "packages":
        return "Пакеты";
      case "repository_hooks":
        return "Вебхуки репозитория";
      case "repository_projects":
        return "Проекты репозитория";
      case "repository_custom_properties":
        return "Дополнительные свойства репозитория";
      case "repository_advisories":
        return "Рекомендации по безопасности";
      case "vulnerability_alerts":
        return "Уведомления об уязвимостях";
      case "security_events":
        return "События безопасности";
      case "agent_tasks":
        return "Задачи GitHub Agent";
      case "members":
        return "Участники и команды";
      case "organization_projects":
        return "Проекты организации";
      case "organization_administration":
        return "Управление организацией";
      case "organization_actions_variables":
        return "Переменные организации";
      case "organization_secrets":
        return "Секреты организации";
      default:
        return "Дополнительное разрешение: " + key;
    }
  }
}
