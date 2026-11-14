package app.githubble.mobile.ui;

import android.app.*;
import app.githubble.mobile.agent.*;
import org.json.*;

/** Task, changes, CI, independent delivery evidence and explicit continuation in one place. */
public final class TaskWorkPanel {
    private final Activity activity;private final AgentProjectStore store;private final String account,id;
    private final java.util.function.BooleanSupplier current;private final Runnable refresh;
    private final java.util.function.Consumer<String> changes;
    public TaskWorkPanel(Activity activity,AgentProjectStore store,String account,String id,
            java.util.function.BooleanSupplier current,Runnable refresh,java.util.function.Consumer<String> changes){
        this.activity=activity;this.store=store;this.account=account;this.id=id;this.current=current;this.refresh=refresh;this.changes=changes;
    }
    public void show(){if(!current.getAsBoolean())return;JSONObject t=store.tasks.get(account,id);
        String[] actions={"Открыть обсуждение","Изменения и diff","CI и проверка результата","Разрешить продолжение","Увеличить текущий бюджет до 25 раундов"};
        new AlertDialog.Builder(activity).setTitle(t.optString("title")+" · "+state(t.optString("state")))
            .setItems(actions,(d,n)->{if(!current.getAsBoolean())return;
                if(n==0){store.switchConversation(account,id);refresh.run();}
                if(n==1){store.switchConversation(account,id);refresh.run();changes.accept(id);}
                if(n==2)details();
                if(n==3&&app.githubble.mobile.agent.AgentRunRegistry.current(account)!=null&&app.githubble.mobile.agent.AgentRunRegistry.current(account).active()){
                    android.widget.Toast.makeText(activity,"Сначала остановите текущий запуск",android.widget.Toast.LENGTH_LONG).show();return;
                }
                if(n==3)new AlertDialog.Builder(activity).setTitle("Разрешить продолжение?")
                    .setMessage("Дополнительно: до 12 раундов, 100 инструментов и 180000 токенов; CI — до 8 сборок и 5 исправлений. Денежный лимит сохраняется. Сначала устраните указанную внешнюю причину. Неопределённые операции сохраняют блокировку повторной отправки.")
                    .setPositiveButton("Разрешить",(x,y)->{if(current.getAsBoolean()){store.tasks.authorizeContinuation(account,id);refresh.run();show();}}).setNegativeButton("Отмена",null).show();
                if(n==4){store.tasks.update(account,id,v->{try{JSONObject b=v.getJSONObject("budget");b.put("maxRounds",b.optInt("roundWindowStart")+25).put("maxTokens",Math.max(b.optLong("maxTokens",180000),360000));}catch(JSONException e){throw new IllegalStateException(e);}});show();}
            }).setNegativeButton("Закрыть",null).show();
    }
    private void details(){JSONObject t=store.tasks.get(account,id);JSONObject b=t.optJSONObject("budget"),ci=t.optJSONObject("ci"),delivery=t.optJSONObject("delivery");
        StringBuilder text=new StringBuilder(t.optString("goal")).append("\n\nСостояние: ").append(state(t.optString("state")))
            .append("\nВетка: ").append(t.optString("workingBranch")).append("\nCommit: ").append(t.optString("confirmedCommitSha",t.optString("sourceCommitSha")))
            .append("\nРаунды: ").append(b.optInt("rounds")).append(" / ").append(b.optInt("maxRounds"))
            .append("\nИнструменты: ").append(b.optInt("tools")).append(" / ").append(b.optInt("maxTools"));
        if(!t.optString("recoveryNote").isEmpty())text.append("\n\n").append(t.optString("recoveryNote"));
        if(ci!=null){JSONObject retry=ci.optJSONObject("retry");text.append("\n\nCI: ").append(ci.optString("state")).append(" · run #").append(ci.optLong("runId"))
            .append("\nSHA: ").append(ci.optString("sha"));if(retry!=null)text.append("\nСборок: ").append(retry.optInt("buildRuns")).append(" / 8; исправлений: ").append(retry.optInt("autoFixes")).append(" / 5");
            JSONObject d=ci.optJSONObject("decision");if(d!=null)text.append("\n").append(d.optString("reason"));}
        text.append("\n\nРезультат: ").append(delivery!=null&&delivery.optBoolean("accepted")?"подтверждён":"ожидает проверки");
        if(delivery!=null)text.append("\n").append(delivery.optJSONArray("issues"));
        android.widget.TextView content=new android.widget.TextView(activity);content.setText(text.toString());content.setPadding(24,16,24,16);content.setTextIsSelectable(true);
        android.widget.ScrollView scroll=new android.widget.ScrollView(activity);scroll.addView(content);
        new AlertDialog.Builder(activity).setTitle("Рабочая панель задачи").setView(scroll).setPositiveButton("Назад",(d,w)->show()).show();
    }
    public static String state(String value){switch(value){case "READY":return "Готова к работе";case "RUNNING":return "Выполняется";case "REVIEW":return "Ожидает проверки";case "DONE":return "Результат подтверждён";case "INTERRUPTED":return "Прервана · можно продолжить";case "WAITING_EXTERNAL":return "Нужны внешние действия";case "WAITING_BUDGET":return "Нужно разрешение на продолжение";case "FAILED":return "Остановлена с ошибкой";default:return value;}}
}
