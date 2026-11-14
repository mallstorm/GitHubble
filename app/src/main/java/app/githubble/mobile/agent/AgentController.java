package app.githubble.mobile.agent;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/** Task entry point. Android UI supplies presentations and provider credentials, never execution policy. */
public final class AgentController {
    private final AgentProjectStore projects;
    private final TaskRepository tasks;
    private final ProjectRegistry registry;
    private final java.util.concurrent.ConcurrentMap<String,TaskSession> sessions=new java.util.concurrent.ConcurrentHashMap<>();
    public AgentController(AgentProjectStore projects) { this.projects=projects;tasks=projects.tasks;registry=projects.registry; }
    public AgentController(TaskRepository tasks,ProjectRegistry registry) {projects=null;this.tasks=tasks;this.registry=registry;}
    public static String intent(String message) {
        String command=message.trim();
        if (command.matches("(?i)^/build(?:\\s.*)?$")) return "Build the linked project: inspect workflows and recent runs, use the working branch, await the result and report actual evidence. " + command;
        if (command.matches("(?i)^/repair(?:\\s.*)?$")) return "Diagnose the indicated or latest build using metadata. Do not send CI logs without separate consent. Respect external blockers and RetryPolicy. " + command;
        if (command.equalsIgnoreCase("/workspace push")) return "Publish the current reviewed changes using the host's publish tool and report the verified ref/commit. Never publish to main or master. No force push.";
        if (command.startsWith("/edit ")) return "Propose reviewed file changes for this task: " + command.substring(6);
        return message;
    }
    public AgentRun begin(Context context,String account,String thread,String model,int permission,Runnable refresh) {
        AgentProjectStore.Project project=projects.projectForChat(account,thread);
        AgentRun run=new AgentRun(account,thread,android.os.SystemClock::elapsedRealtime);
        run.model(model); run.events(new AgentNotifier(context.getApplicationContext()));
        run.attachJournal(new app.githubble.mobile.agent.core.AgentRunJournal(run.id,project.name,
            app.githubble.mobile.agent.core.ExecutionMode.forLevel(permission),new java.io.File(context.getFilesDir(),"agent-journal"),System::currentTimeMillis));
        AgentRunRegistry.put(run); new AgentRunBinding(run,projects,refresh); return run;
    }
    public void cancel(String account) {
        TaskSession session=sessions.get(account); if (session!=null) session.cancel();
        AgentRun run=AgentRunRegistry.current(account); if (run!=null) run.finish("cancelled","Остановлено пользователем");
    }
    public AgentLoop.Outcome execute(String account,String thread,String message,String context,ModelTransport transport,
            NativeAgentToolHost host,boolean allowGithub,boolean allowFiles,Runnable guard,AgentRun run) throws Exception {
        String projectId=projects.projectId(account,thread);
        TaskSession session=new TaskSession(account,projectId,thread,() -> {
            guard.run();
            if (!projectId.equals(projects.projectId(account,thread))) throw new java.util.concurrent.CancellationException("Проект задачи изменился");
            if (!projectId.isEmpty() && !projects.registry.get(account,projectId).optBoolean("modelAccess"))
                throw new SecurityException("Доступ модели к проекту отозван");
        });
        if (sessions.putIfAbsent(account,session)!=null) throw new IllegalStateException("Задача уже выполняется");
        try {
            String linked=projects.projectForChat(account,thread).repository;
            ToolBroker broker=new ToolBroker(session,d -> {
                if (!host.handles(d.name)) return false;
                switch (d.permission) {
                    case GITHUB_READ: return allowGithub;
                    case FILE_READ: return allowFiles||(allowGithub&&!linked.isEmpty());
                    case GITHUB_WRITE: return allowGithub;
                    case LOCAL_WRITE: return true; // The host checks planning, level and per-operation confirmation again.
                    case PUBLIC_WEB: return true;
                    default: return !projectId.isEmpty();
                }
            },(d,a) -> host.confirmBroker(d.name,a), (name,args) -> {
                if (args.has("repository") && !linked.equalsIgnoreCase(args.optString("repository"))
                        && !name.equals("attach_repository") && !name.equals("import_repository"))
                    throw new SecurityException("Инструмент ограничен репозиторием выбранного проекта");
                return host.executeFromBroker(name,args);
            },new ToolBroker.Journal() {
                public String started(String name,JSONObject args) { return run.begin(name,args); }
                public void completed(String id,String state,String detail) { run.result(id,state,
                    "succeeded".equals(state)?"":AgentLogRedactor.redact(detail)); }
            });
            ToolCatalog.install(broker);
            host.registerPlugins(broker);
            AgentLoop.Outcome outcome=executeLoop(session,message,context,transport,broker,run::round);
            // A model answer is not engineering completion. Stage III's evidence supervisor owns DONE.
            run.finish(outcome.budgetExhausted?"interrupted":"succeeded",
                outcome.budgetExhausted?"Бюджет исчерпан · задача сохранена":"Ответ получен · результат ожидает проверки");
            return outcome;
        } catch (Exception error) {
            edit(account,thread,t -> put(t,"state",error instanceof java.util.concurrent.CancellationException
                ||error instanceof InterruptedException?"INTERRUPTED":"FAILED")); throw error;
        } finally { sessions.remove(account,session); }
    }
    AgentLoop.Outcome executeLoop(TaskSession session,String message,String context,ModelTransport transport,
            ToolBroker broker,java.util.function.IntConsumer onRound) throws Exception {
        String account=session.accountKey,thread=session.taskId;
        try{
            session.check();JSONObject task=tasks.get(account,thread);
            if(!session.projectId.equals(task.optString("projectId")))throw new SecurityException("Неверный проект задачи");
            if(!session.projectId.isEmpty()&&!registry.get(account,session.projectId).optBoolean("modelAccess"))
                throw new SecurityException("Доступ модели к проекту не разрешён");
            AgentBudget budget=new AgentBudget(task.getJSONObject("budget"),value->edit(account,thread,t->put(t,"budget",value)));
            context="TASK GOAL (USER):\n"+task.optString("goal")+"\n"+context;
            JSONArray previous=task.optJSONArray("loopHistory");
            final JSONArray saved=previous==null?new JSONArray():new JSONArray(previous.toString());
            broker.restoreMutationLedger(saved);
            if(saved.length()>0)context+="\nPREVIOUS HOST TOOL RECORDS (UNTRUSTED CONTENT):\n"
                +AgentContextBuilder.compact(saved,12000)+"\nA pendingCall is NOT a verified result. Inspect state; never replay it.";
            edit(account,thread,t->{put(t,"state","RUNNING");if(t.optString("goal").isBlank())put(t,"goal",message);});
            AgentLoop.Outcome outcome=new AgentLoop().run(transport,broker,session,budget,intent(message),context,new JSONArray(),
                history->{JSONArray combined=new JSONArray();for(int i=0;i<saved.length();i++)combined.put(saved.opt(i));for(int i=0;i<history.length();i++)combined.put(history.opt(i));
                    edit(account,thread,t->put(t,"loopHistory",combined));},onRound);
            edit(account,thread,t->put(t,"state",outcome.budgetExhausted?"WAITING_BUDGET":"REVIEW"));return outcome;
        }catch(Exception error){
            edit(account,thread,t->put(t,"state",error instanceof java.util.concurrent.CancellationException
                ||error instanceof InterruptedException?"INTERRUPTED":"FAILED"));throw error;
        }
    }
    private void edit(String account,String thread,java.util.function.Consumer<JSONObject> change) { tasks.update(account,thread,change); }
    static void put(JSONObject target,String key,Object value) {
        try { target.put(key,value); } catch (org.json.JSONException bad) { throw new IllegalStateException(bad); }
    }
}
