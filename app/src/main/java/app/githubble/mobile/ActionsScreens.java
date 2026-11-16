package app.githubble.mobile;

import android.app.*;
import android.os.*;
import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.text.TextUtils;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.RelativeSizeSpan;
import android.text.util.Linkify;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import app.githubble.mobile.auth.OAuthManager;
import app.githubble.mobile.auth.GitHubAccountStore;
import app.githubble.mobile.mirror.MirrorManager;
import app.githubble.mobile.mirror.GoogleMirrorCloudStore;
import app.githubble.mobile.mirror.MirrorCloudStore;
import app.githubble.mobile.ui.MirrorManagerScreen;
import app.githubble.mobile.ui.MirrorSetupWizardScreen;
import app.githubble.mobile.auth.GitHubAppInstallationGate;
import app.githubble.mobile.auth.DeviceCodeAssist;
import app.githubble.mobile.network.GitHubApiClient;
import app.githubble.mobile.inbox.InboxItem;
import app.githubble.mobile.inbox.InboxService;
import app.githubble.mobile.network.GitHubGraphQlClient;
import app.githubble.mobile.security.SecureStore;
import app.githubble.mobile.ui.HomeScreen;
import app.githubble.mobile.ui.BottomNavigationBar;
import app.githubble.mobile.ui.AgentChatScreen;
import app.githubble.mobile.ui.GitAgentUi;
import app.githubble.mobile.ui.ModelPickerSheet;
import app.githubble.mobile.ui.ProviderHubScreen;
import app.githubble.mobile.agent.AgentProjectStore;
import app.githubble.mobile.agent.GitHubAgentBridge;
import app.githubble.mobile.agent.AgentBuildArtifactController;
import app.githubble.mobile.agent.AgentBuildDiagnostics;
import app.githubble.mobile.agent.AgentBuildRepairTicket;
import app.githubble.mobile.agent.AgentGitWorkspace;
import app.githubble.mobile.agent.AgentFilePlan;
import app.githubble.mobile.agent.AgentExecutionEngine;
import app.githubble.mobile.agent.OmniChatProviderCatalog;
import app.githubble.mobile.agent.OmniChatConnectionStore;
import app.githubble.mobile.agent.OAuthSubscriptions;
import app.githubble.mobile.agent.OmniChatChatClient;
import app.githubble.mobile.build.BuildRunFilter;
import app.githubble.mobile.ui.SearchScreen;
import app.githubble.mobile.ui.SettingsScreen;
import app.githubble.mobile.tracking.TrackingDatabase;
import app.githubble.mobile.tracking.TrackingEngine;
import app.githubble.mobile.tracking.TrackingJobService;
import app.githubble.mobile.build.BuildMonitor;
import app.githubble.mobile.build.BuildMonitorJobService;
import app.githubble.mobile.build.BuildArtifactDownloads;
import app.githubble.mobile.build.ActionsStorageManager;
import app.githubble.mobile.tracking.TrackingNotificationHelper;
import app.githubble.mobile.web.GitHubCustomTabs;
import app.githubble.mobile.translation.LanguageDetector;
import app.githubble.mobile.translation.TranslationCache;
import app.githubble.mobile.translation.TranslationEngine;
import app.githubble.mobile.code.CodeHighlighter;
import app.githubble.mobile.diagnostics.TrackingDiagnostics;
import app.githubble.mobile.diagnostics.DiagnosticsExporter;
import app.githubble.mobile.cache.EntityCache;
import app.githubble.mobile.controller.RepositoryController;
import app.githubble.mobile.controller.CommitDetailController;
import app.githubble.mobile.ui.CommitDetailScreen;
import app.githubble.mobile.controller.RepositoryAccessController;
import app.githubble.mobile.controller.AccountOperationsController;
import app.githubble.mobile.controller.RepositoryImportController;
import app.githubble.mobile.controller.ProfileController;
import app.githubble.mobile.controller.TranslationController;
import app.githubble.mobile.state.ScreenStateStore;
import app.githubble.mobile.state.FeatureUiState;
import app.githubble.mobile.util.DeviceCodeFormatter;
import app.githubble.mobile.screen.FeatureControllerRegistry;
import app.githubble.mobile.offline.OfflineActionQueue;
import app.githubble.mobile.offline.OfflineMutationDispatcher;
import app.githubble.mobile.diff.DiffModel;
import app.githubble.mobile.preview.FilePreviewController;
import app.githubble.mobile.backup.PortableSettingsManager;
import app.githubble.mobile.performance.PerformanceMonitor;
import app.githubble.mobile.accessibility.AccessibilityAudit;
import app.githubble.mobile.privacy.UserDataManager;
import app.githubble.mobile.release.ReleaseIntegrity;
import app.githubble.mobile.database.SchemaMigrationRegistry;
import app.githubble.mobile.search.SmartSearchPlanner;
import app.githubble.mobile.search.RepositorySearchRanker;
import app.githubble.mobile.search.SmartSearchHistory;
import app.githubble.mobile.search.SearchMode;
import app.githubble.mobile.search.SearchFilters;
import app.githubble.mobile.search.AdvancedSearchPlanner;
import app.githubble.mobile.repository.RepositoryStore;
import app.githubble.mobile.activity.RepositoryActivityFeed;

/** GitHub Actions billing, storage and cleanup screens, extracted from MainActivity. */
final class ActionsScreens {
    private final MainActivity a;
    ActionsScreens(MainActivity activity) { this.a = activity; }

    private a.String billingUtcTime(long millis){
        if(millis<=0)return "ещё не обновлялось";
        java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm",Locale.getDefault());
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(millis))+" UTC";
    }

    void showActionsRepositoryPicker(a.String title,java.util.function.Consumer<a.String> action){
        if(!a.isSignedIn()){a.accountRequiredCard("Подключите GitHub для доступа к репозиториям.");return;}
        a.io.execute(()->{
            try{
                LinkedHashSet<a.String> names=new LinkedHashSet<>();
                for(int page=1;page<=5;page++){
                    JSONArray repos=a.api.getArray("https://api.github.com/user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=updated&per_page=100&page="+page);
                    for(int i=0;i<repos.length();i++){
                        JSONObject item=repos.optJSONObject(i);
                        a.String full=item==null?"":item.optString("full_name","");
                        if(full.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))names.add(full);
                    }
                    if(repos.length()<100)break;
                }
                a.String[] values=names.toArray(new a.String[0]);
                a.runOnUiThread(()->{
                    if(values.length==0){a.toast("Нет доступных репозиториев");return;}
                    new AlertDialog.Builder(a).setTitle(title)
                            .setItems(values,(d,which)->action.accept(values[which]))
                            .setNegativeButton("Отмена",null).show();
                });
            }catch(Exception e){a.runOnUiThread(()->a.errorDialog(title,e.getMessage()));}
        });
    }

    View labeledField(a.String label,View field){
        LinearLayout box=new LinearLayout(a);box.setOrientation(LinearLayout.VERTICAL);
        TextView caption=a.text(label,12,true);caption.setTextColor(a.MUTED);box.addView(caption);box.addView(field);return box;
    }

    EditText numberField(a.String hint,a.String value){
        EditText field=new EditText(a);field.setSingleLine(true);field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setHint(hint);field.setText(value);return field;
    }

    /** One form for the repository cache quotas (size and retention), shown only when GitHub allows them. */
    void addCacheQuotaCard(a.String full,ActionsStorageManager manager){
        a.sectionTitle("Квоты кэша","Отдельно от артефактов. По умолчанию GitHub даёт 10 ГБ и 7 дней на репозиторий.");
        final long generation=a.screenGeneration.get();
        final LinearLayout host=a.content;
        final a.String account=a.currentAccountId();
        LinearLayout box=new LinearLayout(a);box.setOrientation(LinearLayout.VERTICAL);
        TextView status=a.text("Получаем настройки GitHub…",12,false);status.setTextColor(a.MUTED);box.addView(status);
        EditText sizeInput=numberField("10","");EditText daysInput=numberField("7","");
        sizeInput.setEnabled(false);daysInput.setEnabled(false);
        LinearLayout.LayoutParams fieldParams=new LinearLayout.LayoutParams(-1,-2);fieldParams.setMargins(0,a.dp(8),0,0);
        box.addView(labeledField("Максимальный размер кэша, ГБ (1–10000)",sizeInput),fieldParams);
        box.addView(labeledField("Срок хранения кэша, дней (1–400)",daysInput),fieldParams);
        Button save=a.button("Сохранить квоты кэша");save.setEnabled(false);
        LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(-1,a.dp(48));saveParams.setMargins(0,a.dp(10),0,0);
        box.addView(save,saveParams);
        a.card(box);
        final int[] loaded={-1,-1};
        final Runnable load=()->a.io.execute(()->{
            int size=-1,days=-1;a.String sizeError="",daysError="";
            try{size=manager.loadCacheStorageLimitGb(full);}catch(Exception e){sizeError=e.getMessage();}
            try{days=manager.loadCacheRetentionDays(full);}catch(Exception e){daysError=e.getMessage();}
            final int sizeValue=size,daysValue=days;final a.String sizeFailure=sizeError,daysFailure=daysError;
            a.runOnUiThread(()->{
                if(generation!=a.screenGeneration.get()||host!=a.content||!account.equals(a.currentAccountId()))return;
                loaded[0]=sizeValue;loaded[1]=daysValue;
                if(sizeValue>0)sizeInput.setText(a.String.valueOf(sizeValue));
                if(daysValue>0)daysInput.setText(a.String.valueOf(daysValue));
                sizeInput.setEnabled(sizeValue>0);daysInput.setEnabled(daysValue>0);
                save.setEnabled(sizeValue>0||daysValue>0);
                if(sizeValue>0||daysValue>0)status.setText("Текущие значения GitHub: "+(sizeValue>0?sizeValue+" ГБ":"размер недоступен")+" · "+(daysValue>0?daysValue+" дн.":"срок недоступен"));
                else status.setText("Квоты кэша недоступны. "+app.githubble.mobile.build.ActionsErrorText.describe(sizeFailure.isEmpty()?daysFailure:sizeFailure));
            });
        });
        save.setOnClickListener(v->{
            final int size,days;
            try{size=Integer.parseInt(sizeInput.getText().toString().trim());days=Integer.parseInt(daysInput.getText().toString().trim());}
            catch(Exception ex){a.toast("Введите целые числа");return;}
            if(size<1||size>10000){a.toast("Размер кэша: от 1 до 10000 ГБ");return;}
            if(days<1||days>400){a.toast("Срок хранения: от 1 до 400 дней");return;}
            final boolean sizeChanged=sizeInput.isEnabled()&&size!=loaded[0],daysChanged=daysInput.isEnabled()&&days!=loaded[1];
            if(!sizeChanged&&!daysChanged){a.toast("Значения не изменились");return;}
            Runnable apply=()->{
                save.setEnabled(false);status.setText("Сохраняем в GitHub…");
                a.io.execute(()->{
                    StringBuilder problems=new StringBuilder();
                    if(sizeChanged)try{manager.setCacheStorageLimitGb(full,size);}catch(Exception e){problems.append("Размер: ").append(app.githubble.mobile.build.ActionsErrorText.describe(e.getMessage())).append('\n');}
                    if(daysChanged)try{manager.setCacheRetentionDays(full,days);}catch(Exception e){problems.append("Срок: ").append(app.githubble.mobile.build.ActionsErrorText.describe(e.getMessage())).append('\n');}
                    final a.String failure=problems.toString().trim();
                    a.runOnUiThread(()->{
                        if(generation!=a.screenGeneration.get()||host!=a.content)return;
                        save.setEnabled(true);
                        if(failure.isEmpty()){status.setText("Сохранено в GitHub.");a.toast("Квоты кэша сохранены");}
                        else status.setText("Не всё сохранено.\n"+failure);
                    });
                });
            };
            if(sizeChanged&&size>10)
                new AlertDialog.Builder(a).setTitle("Возможны списания GitHub")
                        .setMessage("Кэш больше бесплатных 10 ГБ на репозиторий может оплачиваться. Установить лимит "+size+" ГБ?")
                        .setNegativeButton("Отмена",null).setPositiveButton("Подтвердить",(d,w)->apply.run()).show();
            else apply.run();
        });
        load.run();
    }

    void showActionsBillingCost(a.String full){
        if(!full.isEmpty()&&!full.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")){
            a.errorDialog("Billing","Некорректный репозиторий");return;
        }
        a.setCurrentScreen(()->showActionsBillingCost(full));
        a.shell("Оплата и диагностика",false);
        a.settingsPanelHeader("Оплата GitHub","Расходы, бюджеты и источники данных",R.drawable.ic_settings);
        if(!a.isSignedIn()){a.accountRequiredCard("Подключите GitHub для просмотра Billing.");return;}
        final long generation=a.screenGeneration.get();
        final a.String account=a.currentAccountId();
        final LinearLayout host=a.content;

        a.sectionTitle("Расходы на GitHub Actions",null);
        LinearLayout costCard=a.billingSurface();
        LinearLayout costHeader=a.billingHeader(R.drawable.ic_history,"GitHub Actions",
                full.isEmpty()?"Все доступные проекты":"Проект "+full,0xFF7A43D1);
        costCard.addView(costHeader);
        TextView period=a.text("Период: определяем…",11,false);period.setTextColor(a.MUTED);period.setPadding(0,a.dp(8),0,a.dp(5));
        costCard.addView(period);
        LinearLayout grossRow=new LinearLayout(a);grossRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView grossLabel=a.text("Gross (использование)",13,false),grossValue=a.text("—",16,true);grossValue.setGravity(Gravity.END);
        grossRow.addView(grossLabel,new LinearLayout.LayoutParams(0,a.dp(42),1));grossRow.addView(grossValue,new LinearLayout.LayoutParams(a.dp(130),a.dp(42)));
        costCard.addView(grossRow);
        LinearLayout discountRow=new LinearLayout(a);discountRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView discountLabel=a.text("Скидка GitHub",13,false),discountValue=a.text("—",16,true);discountValue.setTextColor(a.GREEN);discountValue.setGravity(Gravity.END);
        discountRow.addView(discountLabel,new LinearLayout.LayoutParams(0,a.dp(42),1));discountRow.addView(discountValue,new LinearLayout.LayoutParams(a.dp(130),a.dp(42)));
        costCard.addView(discountRow);
        View divider=new View(a);divider.setBackgroundColor(a.BORDER);costCard.addView(divider,new LinearLayout.LayoutParams(-1,a.dp(1)));
        LinearLayout netRow=new LinearLayout(a);netRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView netLabel=a.text("Итого к оплате (Net)",15,true),netValue=a.text("—",19,true);netValue.setGravity(Gravity.END);
        netRow.addView(netLabel,new LinearLayout.LayoutParams(0,a.dp(48),1));netRow.addView(netValue,new LinearLayout.LayoutParams(a.dp(140),a.dp(48)));
        costCard.addView(netRow);
        TextView reportNote=a.text("Отчёт основан на GitHub Billing usage summary. Финальный счёт GitHub может обновляться с задержкой.",10,false);
        reportNote.setTextColor(a.MUTED);costCard.addView(reportNote);
        a.card(costCard);

        a.sectionTitle("Бюджеты",null);
        a.settingsActionRow("Настроить бюджеты GitHub","Лимиты, уведомления и правила",
                ()->a.openSecureGitHubPage("https://github.com/settings/billing"));
        a.settingsActionRow("Budgets and alerts","Открыть официальные настройки GitHub",
                ()->a.openSecureGitHubPage("https://github.com/settings/billing"));
        a.settingsActionRow("Открыть Billing & payment","Управление оплатой только на GitHub",
                ()->a.openSecureGitHubPage(AccountOperationsController.BILLING_PAGE));
        TextView budgetWarning=a.text("Локальные лимиты GitHubble не останавливают биллинг GitHub. Для запрета платного использования используйте официальный Billing → Budgets and alerts.",11,false);
        budgetWarning.setTextColor(0xFF9A6500);budgetWarning.setBackground(a.round(a.blend(a.CARD,0xFFFFB020,0.10f),14,0,0));
        budgetWarning.setPadding(a.dp(12),a.dp(10),a.dp(12),a.dp(10));a.content.addView(budgetWarning);

        a.sectionTitle("Доступ и диагностика",null);
        LinearLayout diagnostics=a.billingSurface();
        TextView billingState=a.text("Проверяется",11,true);
        TextView planState=a.text("Проверяется",11,true);
        TextView privateState=a.text("Через GitHub App",11,true);privateState.setTextColor(a.GREEN);
        TextView inventoryState=a.text(a.prefs.getLong(a.billingCacheKey("inventory_at"),0L)>0
                ?(a.prefs.getBoolean(a.billingCacheKey("partial"),true)?"Частичная":"Проверена"):"Не запускалась",11,true);
        TextView updateState=a.text(billingUtcTime(Math.max(
                a.prefs.getLong(a.billingCacheKey("billing_at"),0L),
                a.prefs.getLong(a.billingCacheKey("inventory_at"),0L))),11,false);
        a.addBillingStatusRow(diagnostics,R.drawable.ic_lock,"Billing API",billingState,a.GREEN);
        a.addBillingStatusRow(diagnostics,R.drawable.ic_lock,"Plan permission",planState,a.GREEN);
        a.addBillingStatusRow(diagnostics,R.drawable.ic_lock,"Доступ к приватным репозиториям",privateState,a.GREEN);
        a.addBillingStatusRow(diagnostics,R.drawable.ic_repository,"Инвентаризация",inventoryState,0xFF7A43D1);
        a.addBillingStatusRow(diagnostics,R.drawable.ic_history,"Последнее обновление",updateState,a.MUTED);
        a.card(diagnostics);

        a.sectionTitle("Источники данных и цвета",null);
        LinearLayout sources=a.billingSurface();
        TextView official=a.text("●  Официальный GitHub — данные GitHub Billing API",11,true);official.setTextColor(a.GREEN);sources.addView(official);
        TextView calculated=a.text("●  Расчёт GitHubble — производные значения: остаток, проценты",11,true);calculated.setTextColor(0xFF3478D4);calculated.setPadding(0,a.dp(7),0,0);sources.addView(calculated);
        TextView inventory=a.text("●  Инвентаризация — найденные артефакты и cache по репозиториям",11,true);inventory.setTextColor(0xFF7A43D1);inventory.setPadding(0,a.dp(7),0,0);sources.addView(inventory);
        a.card(sources);

        a.sectionTitle("Диагностика и устранение проблем",null);
        GridLayout tools=new GridLayout(a);tools.setColumnCount(a.adaptiveColumns(2));
        a.addBillingQuickAction(tools,R.drawable.ic_lock,"Права GitHub App","Проверить доступ к приватным репозиториям",a::openPrivateRepositoryAccess);
        a.addBillingQuickAction(tools,R.drawable.ic_repository,"Выбрать репозитории","Настроить доступ GitHub App",a::openPrivateRepositoryAccess);
        a.addBillingQuickAction(tools,R.drawable.ic_history,"Сбросить кэш Billing","Запросить свежие данные",()->{
            a.api.invalidatePrefix("https://api.github.com/users/");
            a.api.invalidatePrefix("https://api.github.com/user");
            a.toast("Кэш Billing сброшен");
            showActionsBillingCost(full);
        });
        a.addBillingQuickAction(tools,R.drawable.ic_settings,"Повторить запрос","Обновить отчёт и диагностику",()->showActionsBillingCost(full));
        a.content.addView(tools,new LinearLayout.LayoutParams(-1,-2));
        a.settingsActionRow("Подробности API","Показать источники запросов",()->new AlertDialog.Builder(a)
                .setTitle("Billing API")
                .setMessage("Профиль и тариф:\nGET /user\n\nИспользование Actions:\nGET /users/{login}/settings/billing/usage/summary?year=…&month=…&product=Actions\n\nХранилище:\nGET /repos/{owner}/{repo}/actions/artifacts\nGET /repos/{owner}/{repo}/actions/caches")
                .setPositiveButton("OK",null).show());

        a.io.execute(()->{
            try{
                JSONObject profile=a.api.getJsonFresh("https://api.github.com/user");
                a.String login=profile.optString("login","");
                JSONObject planJson=profile.optJSONObject("plan");
                a.String planName=planJson==null?"":app.githubble.mobile.ui.JsonText.of(planJson,"name","");
                if(!login.matches("[A-Za-z0-9-]+"))throw new IllegalStateException("Не удалось определить аккаунт GitHub");
                java.time.LocalDate month=java.time.LocalDate.now(java.time.ZoneOffset.UTC);
                a.String url="https://api.github.com/users/"+login+"/settings/billing/usage/summary?year="+month.getYear()
                        +"&month="+month.getMonthValue()+"&product=Actions"
                        +(full.isEmpty()?"":"&repository="+Uri.encode(full));
                JSONObject report=a.api.getJson(url);
                JSONArray items=report.optJSONArray("usageItems");
                if(items==null)throw new IllegalStateException("GitHub Billing API не вернул usageItems");
                double gross=0d,discount=0d,net=0d;
                boolean complete=true;
                for(int i=0;i<items.length();i++){
                    JSONObject item=items.optJSONObject(i);
                    if(item==null||!"Actions".equalsIgnoreCase(item.optString("product","")))continue;
                    if(!item.has("grossAmount")||!item.has("discountAmount")||!item.has("netAmount")){
                        complete=false;continue;
                    }
                    gross+=item.optDouble("grossAmount",0d);
                    discount+=item.optDouble("discountAmount",0d);
                    net+=item.optDouble("netAmount",0d);
                }
                final boolean totalsComplete=complete;
                final double grossFinal=gross,discountFinal=discount,netFinal=net;
                final a.String plan=planName;
                final a.String periodText=month.getYear()+"-"+a.String.format(Locale.US,"%02d",month.getMonthValue());
                a.runOnUiThread(()->{
                    if(generation!=a.screenGeneration.get()||host!=a.content||!account.equals(a.currentAccountId()))return;
                    period.setText("Период: "+periodText+" UTC · Тариф: "+(plan.isEmpty()?"—":plan));
                    billingState.setText("Доступен");billingState.setTextColor(a.GREEN);
                    planState.setText(plan.isEmpty()?"Read":"Read · "+plan);planState.setTextColor(a.GREEN);
                    if(totalsComplete){
                        grossValue.setText("$"+a.String.format(Locale.US,"%.4f",grossFinal));
                        discountValue.setText((discountFinal>0?"−":"")+"$"+a.String.format(Locale.US,"%.4f",Math.abs(discountFinal)));
                        netValue.setText("$"+a.String.format(Locale.US,"%.4f",netFinal));
                    }else{
                        grossValue.setText("Неполно");discountValue.setText("Неполно");netValue.setText("Неполно");
                    }
                    long now=System.currentTimeMillis();
                    a.prefs.edit().putLong(a.billingCacheKey("billing_at"),now).apply();
                    updateState.setText(billingUtcTime(now));
                });
            }catch(Exception error){
                a.runOnUiThread(()->{
                    if(generation!=a.screenGeneration.get()||host!=a.content)return;
                    billingState.setText("Недоступен");billingState.setTextColor(0xFFC34F48);
                    planState.setText("Проверьте Plan: Read");planState.setTextColor(0xFFC34F48);
                    reportNote.setText("Стоимость недоступна: "+(error.getMessage()==null?"неизвестная ошибка":error.getMessage())
                            +". Недоступный результат не считается нулём.");
                });
            }
        });
    }

    /** Account-wide entry point. Each repository owns its own GitHub retention policy. */
    void showActionsStorageRepositories(){
        a.setCurrentScreen(this::showActionsStorageRepositories);
        a.shell("Хранилище Actions",false);
        a.settingsPanelHeader("Хранилище Actions","Артефакты, cache и очистка",R.drawable.ic_repository);
        if(!a.isSignedIn()){a.accountRequiredCard("Подключите GitHub для управления хранилищем.");return;}
        final long generation=a.screenGeneration.get();
        final a.String account=a.currentAccountId();
        final LinearLayout host=a.content;

        a.sectionTitle("Использование хранилища",null);
        LinearLayout summary=a.billingSurface();
        TextView summaryLimit=a.text("Лимит артефактов: определяем…",11,false);summaryLimit.setTextColor(a.MUTED);
        LinearLayout summaryTitle=a.billingHeader(R.drawable.ic_repository,"Артефакты и cache",
                "Сканирование доступных репозиториев",0xFF7A43D1);
        summary.addView(summaryTitle);summary.addView(summaryLimit);
        TextView summaryUsage=a.text("Подготовка инвентаризации…",18,true);summaryUsage.setPadding(0,a.dp(7),0,0);summary.addView(summaryUsage);
        ProgressBar storageProgress=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);
        a.styleBillingProgress(storageProgress,0,a.GREEN);
        LinearLayout.LayoutParams spp=new LinearLayout.LayoutParams(-1,a.dp(8));spp.setMargins(0,a.dp(7),0,a.dp(7));summary.addView(storageProgress,spp);
        LinearLayout summaryMetrics=new LinearLayout(a);
        TextView artifactValue=a.text("—",18,true),artifactDetail=a.text("Артефакты",10,false);
        TextView cacheValue=a.text("—",18,true),cacheDetail=a.text("Cache · отдельная квота",10,false);
        summaryMetrics.addView(a.billingMetric(R.drawable.ic_repository,"Артефакты",artifactValue,artifactDetail,0xFF7A43D1),
                new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams cacheP=new LinearLayout.LayoutParams(0,-2,1);cacheP.setMargins(a.dp(8),0,0,0);
        summaryMetrics.addView(a.billingMetric(R.drawable.ic_repository,"Cache",cacheValue,cacheDetail,a.GREEN),cacheP);
        summary.addView(summaryMetrics);
        TextView summaryNote=a.text("Артефакты сравниваются с лимитом тарифа. Cache не прибавляется к 0,50 ГБ артефактов и отображается отдельно.",10,false);
        summaryNote.setTextColor(a.MUTED);summaryNote.setPadding(0,a.dp(8),0,0);summary.addView(summaryNote);
        a.card(summary);

        a.sectionTitle("Сканирование репозиториев",null);
        LinearLayout scanCard=a.billingSurface();
        TextView scanStatus=a.text("Получаем список репозиториев…",13,true);scanCard.addView(scanStatus);
        ProgressBar scanProgress=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);
        a.styleBillingProgress(scanProgress,0,a.GREEN);
        LinearLayout.LayoutParams scp=new LinearLayout.LayoutParams(-1,a.dp(8));scp.setMargins(0,a.dp(8),0,a.dp(6));scanCard.addView(scanProgress,scp);
        TextView scanMeta=a.text("Проверено 0 репозиториев",11,false);scanMeta.setTextColor(a.MUTED);scanCard.addView(scanMeta);
        Button stop=a.button("Остановить сканирование");stop.setTextColor(0xFFC34F48);stop.setBackground(a.round(a.CARD,13,0xFFC34F48,1));
        LinearLayout.LayoutParams stopP=new LinearLayout.LayoutParams(-1,a.dp(44));stopP.setMargins(0,a.dp(8),0,0);scanCard.addView(stop,stopP);
        a.card(scanCard);

        a.sectionTitle("Репозитории","Открывайте проект для артефактов и retention");
        LinearLayout repoRows=new LinearLayout(a);repoRows.setOrientation(LinearLayout.VERTICAL);a.content.addView(repoRows);

        a.sectionTitle("Менеджер артефактов",null);
        GridLayout managerTools=new GridLayout(a);managerTools.setColumnCount(a.adaptiveColumns(2));
        a.addBillingQuickAction(managerTools,R.drawable.ic_repository,"Сканировать хранилище","Обновить инвентаризацию",this::showActionsStorageRepositories);
        a.addBillingQuickAction(managerTools,R.drawable.ic_history,"Очистка cache","Выбрать репозиторий",
                ()->showActionsRepositoryPicker("Очистка cache",repo->a.navigate(()->showActionsCleanup(repo))));
        a.addBillingQuickAction(managerTools,R.drawable.ic_settings,"Политики retention","Настроить хранение по проекту",
                ()->showActionsRepositoryPicker("Retention репозитория",repo->a.navigate(()->showActionsStorage(repo))));
        a.addBillingQuickAction(managerTools,R.drawable.ic_repository,"Крупные файлы","Открыть артефакты проекта",
                ()->showActionsRepositoryPicker("Артефакты проекта",repo->a.navigate(()->showActionsStorage(repo))));
        a.content.addView(managerTools,new LinearLayout.LayoutParams(-1,-2));
        a.settingsActionRow("Retention и cache","Серверные и локальные правила задаются отдельно для каждого репозитория",
                ()->showActionsRepositoryPicker("Настройки Actions",repo->a.navigate(()->showActionsStorage(repo))));
        a.settingsActionRow("Стоимость и Billing","Перейти к оплате и диагностике",
                ()->a.navigate(()->showActionsBillingCost("")));

        a.io.execute(()->{
            try{
                AccountOperationsController.BillingSnapshot billing=a.accountOperations.loadBillingOverview();
                a.runOnUiThread(()->{
                    if(generation!=a.screenGeneration.get()||host!=a.content||!account.equals(a.currentAccountId()))return;
                    if(billing.storageLimitBytes>0){
                        summaryLimit.setText("Лимит артефактов тарифа "+(billing.planName.isEmpty()?"":billing.planName+": ")
                                +a.formatAccountBytes(billing.storageLimitBytes));
                        summaryLimit.setTag(billing.storageLimitBytes);
                    }else summaryLimit.setText("Лимит артефактов тарифа не определён");
                });
            }catch(Exception ignored){}
        });

        a.accountInventoryCancelled.set(true);
        if(a.accountInventoryFuture!=null&&!a.accountInventoryFuture.isDone())a.accountInventoryFuture.cancel(true);
        final AtomicBoolean stopped=new AtomicBoolean(false);
        a.accountInventoryCancelled=stopped;
        stop.setOnClickListener(v->{
            stopped.set(true);
            if(a.accountInventoryFuture!=null)a.accountInventoryFuture.cancel(true);
            scanStatus.setText("Сканирование остановлено");
            scanMeta.setText("Неполный результат не считается общим объёмом.");
            stop.setEnabled(false);
        });
        a.accountInventoryFuture=a.inventoryIo.submit(()->{
            try{
                AccountOperationsController.StorageInventory result=a.accountOperations.loadStorageInventory((done,total)->a.runOnUiThread(()->{
                    if(generation!=a.screenGeneration.get()||host!=a.content||stopped.get())return;
                    int percent=total==0?0:Math.min(100,(int)((long)done*100/total));
                    a.styleBillingProgress(scanProgress,percent,a.GREEN);
                    scanStatus.setText("Сканирование репозиториев");
                    scanMeta.setText("Проверено "+done+" из "+total+" · "+percent+"%");
                }),()->stopped.get()||Thread.currentThread().isInterrupted()
                        ||generation!=a.screenGeneration.get()||!account.equals(a.currentAccountId()));
                a.runOnUiThread(()->{
                    if(generation!=a.screenGeneration.get()||host!=a.content||stopped.get()||!account.equals(a.currentAccountId()))return;
                    a.rememberInventory(result);
                    stop.setEnabled(false);
                    a.styleBillingProgress(scanProgress,100,a.GREEN);
                    scanStatus.setText(result.isPartial()?"Инвентаризация частичная":"Инвентаризация завершена");
                    scanMeta.setText("Проверено "+result.scanned+" из "+result.total
                            +" · без доступа "+result.inaccessible
                            +(result.truncated?" · лимит сканирования 100 репозиториев":""));
                    artifactValue.setText(a.formatAccountBytes(result.artifactBytes));
                    artifactDetail.setText(result.repositories.stream().mapToInt(x->x.artifacts).sum()+" файлов");
                    cacheValue.setText(a.formatAccountBytes(result.cacheBytes));
                    cacheDetail.setText(result.repositories.stream().mapToInt(x->x.caches).sum()+" объектов · отдельная квота");
                    Object limitTag=summaryLimit.getTag();
                    if(limitTag instanceof Long&&((Long)limitTag)>0){
                        long limit=(Long)limitTag;
                        int percent=(int)Math.round(Math.max(0d,Math.min(100d,result.artifactBytes*100d/limit)));
                        a.styleBillingProgress(storageProgress,percent,a.GREEN);
                        summaryUsage.setText(a.formatAccountBytes(result.artifactBytes)+" из "+a.formatAccountBytes(limit)+" · "+percent+"%");
                    }else summaryUsage.setText("Артефакты "+a.formatAccountBytes(result.artifactBytes)+" · cache "+a.formatAccountBytes(result.cacheBytes));
                    repoRows.removeAllViews();
                    if(result.repositories.isEmpty()){
                        TextView empty=a.text("Доступные данные Actions не найдены. Проверьте разрешения GitHub App.",12,false);
                        empty.setTextColor(a.MUTED);a.card(empty);
                    }else{
                        int shown=0;
                        for(AccountOperationsController.RepositoryUsage usage:result.repositories){
                            if(shown>=40)break;
                            if(usage.totalBytes()<=0&&usage.actionsAccessible)continue;
                            LinearLayout row=a.billingSurface();row.setPadding(a.dp(11),a.dp(9),a.dp(11),a.dp(9));
                            LinearLayout titleRow=new LinearLayout(a);titleRow.setGravity(Gravity.CENTER_VERTICAL);
                            ImageView gh=a.imageIcon(R.drawable.ic_repository,usage.actionsAccessible?a.GREEN:a.MUTED,7);
                            gh.setBackground(a.round(a.blend(a.CARD,usage.actionsAccessible?a.GREEN:a.MUTED,0.08f),10,0,0));
                            titleRow.addView(gh,new LinearLayout.LayoutParams(a.dp(34),a.dp(34)));
                            LinearLayout labels=new LinearLayout(a);labels.setOrientation(LinearLayout.VERTICAL);labels.setPadding(a.dp(9),0,0,0);
                            TextView name=a.text(usage.fullName,13,true);name.setPadding(0,0,0,0);labels.addView(name);
                            TextView meta=a.text(usage.actionsAccessible
                                    ?"Артефакты "+a.formatAccountBytes(usage.artifactBytes)+" · cache "+a.formatAccountBytes(usage.cacheBytes)
                                    :"Нет доступа к Actions",10,false);
                            meta.setTextColor(a.MUTED);meta.setPadding(0,a.dp(2),0,0);labels.addView(meta);
                            titleRow.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
                            TextView open=a.text("Открыть  ›",11,true);open.setTextColor(a.GREEN);open.setGravity(Gravity.CENTER);
                            titleRow.addView(open,new LinearLayout.LayoutParams(a.dp(82),a.dp(40)));
                            row.addView(titleRow);
                            if(usage.actionsAccessible)row.setOnClickListener(v->a.navigate(()->showActionsStorage(usage.fullName)));
                            else row.setAlpha(0.65f);
                            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.setMargins(0,0,0,a.dp(7));repoRows.addView(row,rp);
                            shown++;
                        }
                        if(result.repositories.size()>shown){
                            TextView more=a.text("Показаны крупнейшие "+shown+" репозиториев. Остальные доступны через выбор проекта.",11,false);
                            more.setTextColor(a.MUTED);repoRows.addView(more);
                        }
                    }
                });
            }catch(Exception e){
                a.runOnUiThread(()->{
                    if(generation!=a.screenGeneration.get()||host!=a.content||stopped.get())return;
                    scanStatus.setText("Инвентаризация не завершилась");
                    scanMeta.setText(e.getMessage()==null?"Не удалось получить данные GitHub":e.getMessage());
                    stop.setEnabled(false);
                });
            }
        });
    }

    void showActionsStorage(a.String full){
        if(!full.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")){
            a.errorDialog("Хранилище","Некорректное имя репозитория");return;
        }
        a.setCurrentScreen(()->showActionsStorage(full));
        a.shell("Хранилище Actions",false);
        a.repoSectionHeader(full,"GitHub Actions","Артефакты и правила хранения",R.drawable.ic_android);
        a.addRepositoryActionsTabs(full,1);
        if(!a.isSignedIn()){a.accountRequiredCard("Подключите GitHub для управления Actions.");return;}
        final ActionsStorageManager manager=new ActionsStorageManager(a.api,a.prefs);
        final long generation=a.screenGeneration.get();
        final a.String account=a.currentAccountId();
        final LinearLayout host=a.content;
        ActionsStorageManager.Policy policy=manager.loadPolicy(full);
        EditText keepInput=new EditText(a);keepInput.setSingleLine(true);
        keepInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        keepInput.setHint("Сохранять последних артефактов");keepInput.setText(a.String.valueOf(policy.keepLatest));
        EditText ageInput=new EditText(a);ageInput.setSingleLine(true);
        ageInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        ageInput.setHint("Удалять после, дней");ageInput.setText(a.String.valueOf(policy.minAgeDays));
        EditText limitInput=new EditText(a);limitInput.setSingleLine(true);
        limitInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        limitInput.setHint("Целевой размер, МБ · 0 = все старые");
        limitInput.setText(a.String.valueOf(policy.softLimitMb));
        TextView warning=a.text("Срок хранения GitHub действует для новых файлов. Мягкий лимит и правила очистки на телефоне не являются квотой GitHub и не останавливают платное использование.",12,false);
        warning.setTextColor(a.MUTED);a.card(warning);
        a.sectionTitle("Артефакты","APK/ZIP · скачать или удалить прямо здесь");
        TextView status=a.text("Получаем артефакты…",12,false);status.setTextColor(a.MUTED);a.card(status);
        LinearLayout rows=new LinearLayout(a);rows.setOrientation(LinearLayout.VERTICAL);a.content.addView(rows);
        LinearLayout tools=new LinearLayout(a);
        Button refresh=a.button("↻ Обновить");
        refresh.setOnClickListener(v->showActionsStorage(full));
        Button oldCache=a.button("Кэш Actions");
        oldCache.setOnClickListener(v->a.navigate(()->showActionsCleanup(full)));
        for(Button action:new Button[]{refresh,oldCache}){
            action.setTextSize(12);action.setTextColor(a.GREEN);
            action.setBackground(a.round(a.CARD,13,a.BORDER,1));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,a.dp(45),1f);
            if(action==oldCache)p.setMargins(a.dp(9),0,0,0);
            tools.addView(action,p);
        }
        LinearLayout.LayoutParams toolsP=new LinearLayout.LayoutParams(-1,a.dp(45));
        toolsP.setMargins(0,a.dp(6),0,a.dp(12));
        a.content.addView(tools,toolsP);
        a.io.execute(()->{
            try{
                ActionsStorageManager.Page page=manager.listArtifacts(full,30);
                a.runOnUiThread(()->{
                    if(generation!=a.screenGeneration.get()||host!=a.content||!account.equals(a.currentAccountId()))return;
                    status.setText("Артефакты: "+page.artifacts.size()+" из "+page.totalCount+
                            " · найдено "+a.formatAccountBytes(page.visibleBytes)+
                            (page.incomplete?"\nСписок неполный: просмотр доступен, групповая очистка отключена.":"")+
                            "\nЭто текущий размер файлов, не официальный расход Billing.");
                    if(page.artifacts.isEmpty()){
                        TextView empty=a.text("В этом проекте пока нет артефактов.",12,false);
                        empty.setTextColor(a.MUTED);
                        rows.addView(empty);
                    }
                    final Set<Long> picked=new LinkedHashSet<>();
                    final Map<Long,CheckBox> boxes=new LinkedHashMap<>();
                    final TextView pickedInfo=a.text("",13,true);
                    final Button deleteSelected=a.button("Удалить выбранные");
                    deleteSelected.setBackground(a.round(0xFFC34F48,12,0xFFC34F48,0));
                    final Runnable refreshPicked=()->{
                        long bytes=0;
                        for(ActionsStorageManager.Artifact item:page.artifacts)if(picked.contains(item.id))bytes+=item.sizeBytes;
                        pickedInfo.setText(picked.isEmpty()?"Отметьте артефакты галочками, чтобы удалить их пачкой":"Выбрано: "+picked.size()+" · "+a.formatAccountBytes(bytes));
                        deleteSelected.setEnabled(!picked.isEmpty());
                        deleteSelected.setAlpha(picked.isEmpty()?.45f:1f);
                    };
                    if(!page.artifacts.isEmpty()){
                        LinearLayout bar=new LinearLayout(a);bar.setOrientation(LinearLayout.VERTICAL);
                        bar.setPadding(a.dp(12),a.dp(10),a.dp(12),a.dp(10));bar.setBackground(a.round(a.CARD,12,a.BORDER,1));
                        bar.addView(pickedInfo);
                        LinearLayout quick=new LinearLayout(a);
                        Button all=a.compactButton("Выбрать все"),old=a.compactButton("Все, кроме "+policy.keepLatest+" новых"),none=a.compactButton("Сбросить");
                        all.setOnClickListener(v->{for(CheckBox box:boxes.values())box.setChecked(true);});
                        none.setOnClickListener(v->{for(CheckBox box:boxes.values())box.setChecked(false);});
                        old.setOnClickListener(v->{
                            int index=0;
                            for(ActionsStorageManager.Artifact item:page.artifacts){
                                CheckBox box=boxes.get(item.id);
                                if(box!=null)box.setChecked(index>=policy.keepLatest);
                                index++;
                            }
                        });
                        LinearLayout.LayoutParams q1=new LinearLayout.LayoutParams(0,a.dp(40),1f),q2=new LinearLayout.LayoutParams(0,a.dp(40),1.6f),q3=new LinearLayout.LayoutParams(0,a.dp(40),1f);
                        q2.setMargins(a.dp(6),0,a.dp(6),0);
                        quick.addView(all,q1);quick.addView(old,q2);quick.addView(none,q3);
                        LinearLayout.LayoutParams quickParams=new LinearLayout.LayoutParams(-1,a.dp(40));quickParams.setMargins(0,a.dp(8),0,0);
                        bar.addView(quick,quickParams);
                        LinearLayout.LayoutParams deleteParams=new LinearLayout.LayoutParams(-1,a.dp(46));deleteParams.setMargins(0,a.dp(8),0,0);
                        bar.addView(deleteSelected,deleteParams);
                        LinearLayout.LayoutParams barParams=new LinearLayout.LayoutParams(-1,-2);barParams.setMargins(0,0,0,a.dp(8));
                        rows.addView(bar,barParams);
                        refreshPicked.run();
                        deleteSelected.setOnClickListener(v->{
                            List<ActionsStorageManager.Artifact> chosen=new ArrayList<>();long sum=0;
                            for(ActionsStorageManager.Artifact item:page.artifacts)if(picked.contains(item.id)){chosen.add(item);sum+=item.sizeBytes;}
                            if(chosen.isEmpty())return;
                            StringBuilder names=new StringBuilder();
                            for(int i=0;i<Math.min(8,chosen.size());i++)names.append("\n• ").append(chosen.get(i).name);
                            if(chosen.size()>8)names.append("\n… и ещё ").append(chosen.size()-8);
                            new AlertDialog.Builder(a).setTitle("Удалить выбранные: "+chosen.size()+"?")
                                    .setMessage(full+"\nРазмер: "+a.formatAccountBytes(sum)+names
                                            +"\n\nУдаление необратимо. Исходники и Releases не затрагиваются.")
                                    .setNegativeButton("Отмена",null)
                                    .setPositiveButton("Удалить",(dialog,which)->{
                                        deleteSelected.setEnabled(false);
                                        a.io.execute(()->{
                                            int done=0,failed=0;a.String lastError="";
                                            for(int i=0;i<chosen.size();i++){
                                                final int number=i+1;
                                                a.runOnUiThread(()->pickedInfo.setText("Удаляем "+number+" из "+chosen.size()+"…"));
                                                try{manager.deleteArtifact(full,chosen.get(i).id);done++;}
                                                catch(Exception ex){failed++;lastError=ex.getMessage();}
                                            }
                                            final int deleted=done,errors=failed;
                                            final a.String explanation=lastError;
                                            a.runOnUiThread(()->{
                                                if(generation!=a.screenGeneration.get()||host!=a.content)return;
                                                new AlertDialog.Builder(a).setTitle("Результат удаления")
                                                        .setMessage("Удалено: "+deleted+"\nОшибок: "+errors+
                                                                (explanation.isEmpty()?"":"\n"+app.githubble.mobile.build.ActionsErrorText.describe(explanation)))
                                                        .setPositiveButton("Обновить",(dlg,w)->showActionsStorage(full)).show();
                                            });
                                        });
                                    }).show();
                        });
                    }
                    for(ActionsStorageManager.Artifact artifact:page.artifacts){
                        LinearLayout line=new LinearLayout(a);line.setOrientation(LinearLayout.VERTICAL);
                        line.setPadding(a.dp(12),a.dp(10),a.dp(12),a.dp(10));
                        LinearLayout head=new LinearLayout(a);head.setGravity(Gravity.CENTER_VERTICAL);
                        CheckBox pick=new CheckBox(a);
                        pick.setButtonTintList(android.content.res.ColorStateList.valueOf(a.GREEN));
                        pick.setContentDescription("Выбрать "+artifact.name);
                        boxes.put(artifact.id,pick);
                        pick.setOnCheckedChangeListener((box,on)->{if(on)picked.add(artifact.id);else picked.remove(artifact.id);refreshPicked.run();});
                        head.addView(pick,new LinearLayout.LayoutParams(a.dp(44),a.dp(44)));
                        LinearLayout info=new LinearLayout(a);info.setOrientation(LinearLayout.VERTICAL);
                        info.addView(a.text(artifact.name,14,true));
                        TextView meta=a.text(a.formatAccountBytes(artifact.sizeBytes)+" · "+a.formatGitHubDate(artifact.createdAt)
                                +(artifact.expiresAt.isEmpty()?"":" · до "+a.formatGitHubDate(artifact.expiresAt))
                                +(artifact.expired?" · истёк":""),11,false);
                        meta.setTextColor(a.MUTED);info.addView(meta);
                        head.addView(info,new LinearLayout.LayoutParams(0,-2,1));
                        line.addView(head);
                        Button download=a.compactButton("↓ Скачать ZIP");
                        download.setEnabled(!artifact.expired);
                        download.setOnClickListener(v->{
                            try{
                                JSONObject minimal=new JSONObject().put("id",artifact.id)
                                        .put("name",artifact.name).put("expired",artifact.expired);
                                a.startArtifactDownload(full,minimal);
                            }catch(Exception e){a.errorDialog("Загрузка артефакта",e.getMessage());}
                        });
                        LinearLayout.LayoutParams downloadParams=new LinearLayout.LayoutParams(-1,a.dp(40));downloadParams.setMargins(0,a.dp(6),0,0);
                        line.addView(download,downloadParams);
                        line.setOnClickListener(v->pick.toggle());
                        line.setBackground(a.round(a.CARD,12,a.BORDER,1));
                        LinearLayout.LayoutParams entryParams=new LinearLayout.LayoutParams(-1,-2);
                        entryParams.setMargins(0,0,0,a.dp(8));
                        rows.addView(line,entryParams);
                    }
                    Button preview=a.button("Предпросмотр очистки по правилу");
                    preview.setTextColor(a.GREEN);
                    preview.setBackground(a.round(a.CARD,13,a.GREEN,1));
                    preview.setEnabled(!page.incomplete&&page.artifacts.size()>0);
                    preview.setOnClickListener(v->{
                        try{
                            ActionsStorageManager.Policy p=new ActionsStorageManager.Policy(
                                    Integer.parseInt(keepInput.getText().toString().trim()),
                                    Integer.parseInt(ageInput.getText().toString().trim()),
                                    Integer.parseInt(limitInput.getText().toString().trim()));
                            manager.savePolicy(full,p);
                            List<ActionsStorageManager.Artifact> selected=manager.previewCleanup(page,p);
                            long sum=0;for(ActionsStorageManager.Artifact item:selected)sum+=item.sizeBytes;
                            if(selected.isEmpty()){a.toast("По текущему правилу удалять нечего");return;}
                            final long bytes=sum;
                            StringBuilder names=new StringBuilder();
                            for(int i=0;i<Math.min(8,selected.size());i++)
                                names.append("\n• ").append(selected.get(i).name);
                            if(selected.size()>8)names.append("\n… и ещё ").append(selected.size()-8);
                            new AlertDialog.Builder(a).setTitle("Подтвердить очистку?")
                                    .setMessage("Репозиторий: "+full+"\nОбъектов: "+selected.size()
                                            +"\nРазмер: "+a.formatAccountBytes(bytes)+names
                                            +"\n\nУдалённые файлы не восстановить. Это не изменит уже начисленную сумму Billing.")
                                    .setNegativeButton("Отмена",null)
                                    .setPositiveButton("Удалить",(dialog,which)->{
                                        preview.setEnabled(false);
                                        a.io.execute(()->{
                                            int done=0,failed=0;a.String lastError="";
                                            for(ActionsStorageManager.Artifact item:selected){
                                                try{manager.deleteArtifact(full,item.id);done++;}
                                                catch(Exception ex){failed++;lastError=ex.getMessage();}
                                            }
                                            final int deleted=done,errors=failed;
                                            final a.String explanation=lastError;
                                            a.runOnUiThread(()->{
                                                if(generation!=a.screenGeneration.get()||host!=a.content)return;
                                                new AlertDialog.Builder(a).setTitle("Результат очистки")
                                                        .setMessage("Удалено: "+deleted+"\nОшибок: "+errors+
                                                                (explanation.isEmpty()?"":"\n"+explanation))
                                                        .setPositiveButton("Обновить",(dlg,w)->showActionsStorage(full)).show();
                                            });
                                        });
                                    }).show();
                        }catch(Exception e){a.errorDialog("Правило очистки",e.getMessage());}
                    });
                    rows.addView(preview,new LinearLayout.LayoutParams(-1,a.dp(52)));
                });
            }catch(Exception e){
                a.runOnUiThread(()->{
                    if(generation!=a.screenGeneration.get()||host!=a.content)return;
                    status.setText("Не удалось получить артефакты. "+app.githubble.mobile.build.ActionsErrorText.describe(e.getMessage()));
                });
            }
        });
        a.sectionTitle("Срок хранения GitHub","Общая настройка репозитория · требует Administration: write");
        TextView retentionStatus=a.text("Получаем текущую настройку GitHub…",12,false);
        retentionStatus.setTextColor(a.MUTED);a.card(retentionStatus);
        Button retentionButton=a.button("Изменить срок хранения");
        retentionButton.setEnabled(false);
        retentionButton.setOnClickListener(v->{
            a.String value=retentionStatus.getTag() instanceof a.String?(a.String)retentionStatus.getTag():"";
            int max=400;
            try{max=Integer.parseInt(value);}catch(Exception ignored){}
            final int maxDays=max;
            EditText daysInput=new EditText(a);
            daysInput.setInputType(InputType.TYPE_CLASS_NUMBER);
            daysInput.setHint("Например, 7");
            new AlertDialog.Builder(a).setTitle("Срок хранения · "+full)
                    .setMessage("От 1 до "+maxDays+" дней. Применится только к новым объектам GitHub Actions.")
                    .setView(daysInput).setNegativeButton("Отмена",null)
                    .setPositiveButton("Сохранить",(dialog,which)->{
                        int days;
                        try{days=Integer.parseInt(daysInput.getText().toString().trim());}
                        catch(Exception e){a.toast("Введите целое число дней");return;}
                        if(days<1||days>maxDays){a.toast("Допустимо 1–"+maxDays+" дней");return;}
                        retentionButton.setEnabled(false);
                        retentionStatus.setText("Сохраняем в GitHub…");
                        a.io.execute(()->{
                            try{
                                ActionsStorageManager.Retention updated=manager.setRetention(full,days);
                                a.runOnUiThread(()->{
                                    if(generation!=a.screenGeneration.get()||host!=a.content||!account.equals(a.currentAccountId()))return;
                                    retentionStatus.setText("GitHub: "+updated.days+" дней · максимум "+updated.maxDays);
                                    retentionStatus.setTag(a.String.valueOf(updated.maxDays));
                                    retentionButton.setEnabled(true);
                                });
                            }catch(Exception e){
                                a.runOnUiThread(()->{
                                    if(generation!=a.screenGeneration.get()||host!=a.content)return;
                                    retentionStatus.setText("Не удалось сохранить срок. "+app.githubble.mobile.build.ActionsErrorText.describe(e.getMessage()));
                                    retentionButton.setEnabled(true);
                                });
                            }
                        });
                    }).show();
        });
        a.content.addView(retentionButton,new LinearLayout.LayoutParams(-1,a.dp(49)));
        a.io.execute(()->{
            try{
                ActionsStorageManager.Retention current=manager.loadRetention(full);
                a.runOnUiThread(()->{
                    if(generation!=a.screenGeneration.get()||host!=a.content||!account.equals(a.currentAccountId()))return;
                    retentionStatus.setText("GitHub: "+current.days+" дней · максимум "+current.maxDays);
                    retentionStatus.setTag(a.String.valueOf(current.maxDays));
                    retentionButton.setEnabled(current.maxDays>0);
                });
            }catch(Exception e){
                a.runOnUiThread(()->{
                    if(generation!=a.screenGeneration.get()||host!=a.content)return;
                    retentionStatus.setText("Срок хранения недоступен. "+app.githubble.mobile.build.ActionsErrorText.describe(e.getMessage())+"\nФайлы можно скачивать и удалять отдельно.");
                });
            }
        });
        a.settingsActionRow("Стоимость Actions для проекта","Официальный отчёт GitHub Billing · если разрешён",
                ()->a.navigate(()->showActionsBillingCost(full)));
        a.settingsActionRow("Денежный бюджет GitHub","Настроить в официальном Billing → Budgets and alerts",
                ()->a.openSecureGitHubPage("https://github.com/settings/billing"));
        addCacheQuotaCard(full,manager);
        a.sectionTitle("Правила очистки на устройстве","Применяются только по нажатию «Предпросмотр», затем подтверждение");
        a.card(labeledField("Сохранять последних артефактов",keepInput));
        a.card(labeledField("Удалять старше, дней",ageInput));
        a.card(labeledField("Целевой размер, МБ (0 — удалять все старые)",limitInput));
        Button save=a.button("Сохранить локальное правило");
        save.setOnClickListener(v->{
            try{
                ActionsStorageManager.Policy p=new ActionsStorageManager.Policy(
                        Integer.parseInt(keepInput.getText().toString().trim()),
                        Integer.parseInt(ageInput.getText().toString().trim()),
                        Integer.parseInt(limitInput.getText().toString().trim()));
                manager.savePolicy(full,p);a.toast("Локальное правило сохранено");
            }catch(Exception e){a.errorDialog("Правило очистки",e.getMessage());}
        });
        a.content.addView(save,new LinearLayout.LayoutParams(-1,a.dp(49)));

    }

    void showActionsCleanup(a.String full){
        a.setCurrentScreen(()->showActionsCleanup(full));
        a.shell("Кэш Actions",false);
        a.repoSectionHeader(full,"Кэш Actions","Временные файлы сборок",R.drawable.ic_history);
        Button backToArtifacts=a.button("← Артефакты проекта");
        backToArtifacts.setTextColor(a.GREEN);
        backToArtifacts.setBackground(a.round(a.CARD,13,a.BORDER,1));
        backToArtifacts.setOnClickListener(v->a.navigate(()->showActionsStorage(full)));
        a.content.addView(backToArtifacts,new LinearLayout.LayoutParams(-1,a.dp(48)));
        if(!a.isSignedIn()){a.accountRequiredCard("Подключите GitHub для управления Actions.");return;}
        ProgressBar pb=new ProgressBar(a);a.content.addView(pb);
        a.io.execute(()->{
            try{
                List<AccountOperationsController.CleanupItem> items=a.accountOperations.loadCleanupItems(full);
                items.removeIf(item->!AccountOperationsController.CleanupItem.CACHE.equals(item.kind));
                a.runOnUiThread(()->{
                    a.content.removeView(pb);
                    if(items.isEmpty()){
                        TextView empty=a.text("Кэш Actions пуст.",13,false);empty.setTextColor(a.MUTED);a.card(empty);return;
                    }
                    long total=0L;for(AccountOperationsController.CleanupItem item:items)total+=Math.max(0L,item.sizeBytes);
                    a.settingsRow("Найдено",items.size()+" объектов · "+a.formatAccountBytes(total));
                    TextView note=a.text("Удаляется только кэш сборок; APK, исходники и Releases не затрагиваются.",11,false);note.setTextColor(a.MUTED);a.card(note);
                    List<CheckBox> checks=new ArrayList<>();
                    for(AccountOperationsController.CleanupItem item:items){
                        CheckBox box=new CheckBox(a);
                        a.String type="CACHE";
                        box.setText(type+" · "+item.name+"\n"+a.formatAccountBytes(item.sizeBytes)+(item.extra.isEmpty()?"":" · "+item.extra));
                        box.setTextColor(a.TEXT);box.setTextSize(13);box.setPadding(a.dp(6),a.dp(7),a.dp(6),a.dp(7));
                        checks.add(box);a.card(box);
                    }
                    LinearLayout actions=new LinearLayout(a);
                    Button all=a.button("Выбрать всё");all.setOnClickListener(v->{for(CheckBox b:checks)b.setChecked(true);});
                    Button delete=a.button("Удалить выбранное");delete.setOnClickListener(v->{
                        List<AccountOperationsController.CleanupItem> selected=new ArrayList<>();long bytes=0L;
                        for(int i=0;i<checks.size();i++)if(checks.get(i).isChecked()){selected.add(items.get(i));bytes+=items.get(i).sizeBytes;}
                        if(selected.isEmpty()){a.toast("Ничего не выбрано");return;}
                        long finalBytes=bytes;
                        new AlertDialog.Builder(a).setTitle("Удалить выбранное?")
                                .setMessage("Объектов: "+selected.size()+"\nБудет освобождено примерно: "+a.formatAccountBytes(finalBytes)+"\n\nОперация необратима для выбранных кэшей Actions.")
                                .setNegativeButton("Отмена",null)
                                .setPositiveButton("Удалить",(d,w)->deleteActionsItems(full,selected)).show();
                    });
                    actions.addView(all,new LinearLayout.LayoutParams(0,a.dp(50),1));
                    LinearLayout.LayoutParams dpv=new LinearLayout.LayoutParams(0,a.dp(50),1);dpv.setMargins(a.dp(7),0,0,0);actions.addView(delete,dpv);a.content.addView(actions);
                });
            }catch(Exception e){a.runOnUiThread(()->{a.content.removeView(pb);a.errorCard("Не удалось загрузить Actions",e.getMessage());});}
        });
    }

    void deleteActionsItems(a.String full,List<AccountOperationsController.CleanupItem> selected){
        ProgressDialog dialog=ProgressDialog.show(a,"Очистка Actions","Удаление выбранных объектов…",true,false);
        a.io.execute(()->{
            AccountOperationsController.DeleteResult result=a.accountOperations.deleteCleanupItems(selected);
            a.runOnUiThread(()->{
                dialog.dismiss();
                new AlertDialog.Builder(a).setTitle("Очистка завершена")
                        .setMessage("Удалено: "+result.deleted+"\nОшибок: "+result.failed+"\nОсвобождено: "+a.formatAccountBytes(result.bytesFreed))
                        .setPositiveButton("Обновить",(d,w)->showActionsCleanup(full)).show();
            });
        });
    }
}
