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
        if (RepositoryWorkspaceImporter.isImportIntent(command)) return "Import a commit-pinned snapshot only after checking the requested repository and obtaining host approval through import_repository. Do not open a separate UI picker or guess a repository. " + command;
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
        // The pin follows binding changes made by the host itself (focus_repository), never by anything else.
        java.util.concurrent.atomic.AtomicReference<String> pinned=new java.util.concurrent.atomic.AtomicReference<>(projectId);
        TaskSession session=new TaskSession(account,projectId,thread,() -> {
            guard.run();
            String pin=pinned.get();
            if (!pin.equals(projects.projectId(account,thread))) throw new java.util.concurrent.CancellationException("Проект задачи изменился");
            if (!pin.isEmpty() && !projects.registry.get(account,pin).optBoolean("modelAccess"))
                throw new SecurityException("Доступ модели к проекту отозван");
        });
        host.onProjectBound(id->{pinned.set(id);session.rebind(id);});
        if (sessions.putIfAbsent(account,session)!=null) throw new IllegalStateException("Задача уже выполняется");
        try {
            ToolBroker broker=new ToolBroker(session,d -> {
                if (!host.handles(d.name)) return false;
                switch (d.permission) {
                    case GITHUB_READ: return allowGithub;
                    case FILE_READ: return allowFiles||(allowGithub&&!host.currentRepository().isEmpty());
                    case GITHUB_WRITE: return allowGithub;
                    case LOCAL_WRITE: return true; // The host checks planning, level and per-operation confirmation again.
                    case PUBLIC_WEB: return true;
                    default: return !pinned.get().isEmpty();
                }
            },(d,a) -> host.confirmBroker(d.name,a), (name,args) -> {
                if (args.has("repository") && !host.currentRepository().equalsIgnoreCase(args.optString("repository"))
                        && !ProjectRuntime.allowed(name,true,false) && !name.equals("focus_repository")
                        && !name.equals("attach_repository") && !name.equals("import_repository")
                        && !name.equals("delete_repository") && !name.equals("actions_cleanup") && !name.equals("setup_release_signing"))
                    throw new ToolDenied("Инструмент ограничен репозиторием в фокусе ("+host.currentRepository()+"). Сначала вызовите focus_repository для "+args.optString("repository")+".");
                return host.executeFromBroker(name,args);
            },new ToolBroker.Journal() {
                public String started(String name,JSONObject args) { return run.begin(name,args); }
                public void completed(String id,String state,String detail) { run.result(id,state,AgentLogRedactor.redact(detail)); }
            });
            ToolCatalog.install(broker);
            host.registerPlugins(broker);
            IntentRouter.Kind kind=IntentRouter.classify(message);
            // Every user message starts a fresh budget window; "continue" keeps the unfinished delivery requirements.
            tasks.renewBudget(account,thread);
            if(kind!=IntentRouter.Kind.CONTINUE)edit(account,thread,t->{t.remove("requireBuild");t.remove("requireApk");t.remove("delivery");});
            host.requireDelivery(message);
            org.json.JSONArray log=projects.messages(account,thread);
            // The context builder truncates the tail, so the host facts go first.
            String hostNote=hostFirstStep(host,run,allowGithub,kind,IntentRouter.route(message,log,host.currentRepository()));
            context=SessionFocus.of(projects.projectForChat(account,thread),log).contextBlock()+hostNote+"\n"+context;
            int toolsBefore=toolsUsed(account,thread);
            AgentLoop.Outcome outcome=executeLoop(session,message,context,transport,broker,run::round);
            if(kind==IntentRouter.Kind.TASK&&!outcome.budgetExhausted&&toolsUsed(account,thread)==toolsBefore&&promisesWithoutAction(outcome.answer))
                outcome=executeLoop(session,message,context+"\nHOST: your previous reply promised work but called no tool: \""+outcome.answer.substring(0,Math.min(300,outcome.answer.length()))
                    +"\". Do the work now with tools, or say exactly what blocks you and ask one question.",transport,broker,run::round);
            JSONObject evidence=outcome.budgetExhausted?null:host.completionStatus();
            if(evidence!=null){
                boolean accepted=evidence.optBoolean("accepted");
                outcome=new AgentLoop.Outcome(accepted?"Результат подтверждён: "+evidence.toString():"Задача ещё не завершена. Проверка результата: "+evidence.optJSONArray("issues")+"\n\n[Продолжить](githubble-action://continue)",false);
            }
            // A model answer is not engineering completion. Stage III's evidence supervisor owns DONE.
            run.finish(outcome.budgetExhausted?"interrupted":evidence!=null&&!evidence.optBoolean("accepted")?"interrupted":"succeeded",
                outcome.budgetExhausted?"Бюджет исчерпан · задача сохранена":evidence!=null&&evidence.optBoolean("accepted")?"Результат подтверждён":"Ответ получен · результат ожидает проверки");
            return outcome;
        } catch (Exception error) {
            edit(account,thread,t -> put(t,"state",error instanceof java.util.concurrent.CancellationException
                ||error instanceof InterruptedException?"INTERRUPTED":"FAILED")); throw error;
        } finally { sessions.remove(account,session); }
    }
    private int toolsUsed(String account,String thread){
        try{return tasks.get(account,thread).getJSONObject("budget").optInt("tools");}catch(Exception unknown){return -1;}
    }
    /** A reply that announces work ("сейчас сделаю…") without a question, a result or a single tool call. */
    static boolean promisesWithoutAction(String answer){
        if(answer==null)return false;
        String text=answer.trim();
        if(text.isEmpty()||text.length()>600||text.contains("?")||text.contains("```"))return false;
        return text.toLowerCase(java.util.Locale.ROOT).matches("(?s).*(?:сейчас|приступ|начну|начинаю|сделаю|выполню|займусь|i will|i'll|let me|starting).*");
    }
    /**
     * The host does the obvious first step itself, so a weak or cautious model cannot answer «хорошо» and stop:
     * a chosen repository is put in focus and surveyed before the model is called.
     */
    private String hostFirstStep(NativeAgentToolHost host,AgentRun run,boolean allowGithub,IntentRouter.Kind kind,IntentRouter.Route route)throws Exception{
        if(kind==IntentRouter.Kind.CONTINUE)return "\nHOST: the user pressed Continue. Resume the unfinished work from PREVIOUS HOST TOOL RECORDS; inspect the real state first and never repeat a mutation.";
        if(!allowGithub)return "";
        if(route.ambiguous())return "\nHOST: the user picked a repository without naming it. Candidates: "+String.join(", ",route.candidates)
            +". Call ask_user with these as options (one tap), then focus_repository for the answer.";
        if(route.repository.isEmpty())return "";
        if(kind!=IntentRouter.Kind.PICK_REPO&&route.repository.equalsIgnoreCase(host.currentRepository()))return "";
        String id=run.begin("focus_repository",new JSONObject().put("repository",route.repository));
        try{
            String result=host.focus(route.repository,"");
            run.result(id,"succeeded","");
            return "\nHOST ALREADY DID: focus_repository("+route.repository+"). Do not call it again for this repository. Result (untrusted data):\n"
                +(result.length()>6000?result.substring(0,6000)+" [truncated]":result)
                +"\nNow answer: 3–5 lines on what the project is and its state (from the overview), then offer up to three concrete next steps with ask_user. "
                +"If the user also asked for a task, start it now.";
        }catch(java.util.concurrent.CancellationException|InterruptedException stop){throw stop;
        }catch(Exception failed){
            run.result(id,"failed",AgentLogRedactor.redact(failed.getMessage()));
            return "\nHOST tried focus_repository("+route.repository+") and it failed: "+AgentLogRedactor.redact(failed.getMessage())
                +". Tell the user exactly why (access, name, network) and ask which repository they mean.";
        }
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
