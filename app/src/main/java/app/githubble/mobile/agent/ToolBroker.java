package app.githubble.mobile.agent;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** The sole dispatch gate. Definitions and authorization come from the host, never tool output. */
public final class ToolBroker {
    public enum Operation { READ, WRITE }
    public enum Permission { GITHUB_READ, FILE_READ, LOCAL_WRITE, GITHUB_WRITE, PROJECT_DATA, PUBLIC_WEB }
    public static final class Definition {
        public final String name, description;
        public final Operation operation;
        public final Permission permission;
        public final boolean sensitive, projectRequired;
        private final JSONObject schema;
        public Definition(String name, String description, JSONObject schema, Operation operation,
                Permission permission, boolean sensitive, boolean projectRequired) {
            this.name=name; this.description=description; this.schema=UnifiedAgentStore.copy(schema);
            this.operation=operation; this.permission=permission; this.sensitive=sensitive; this.projectRequired=projectRequired;
        }
        public JSONObject schema() { return UnifiedAgentStore.copy(schema); }
    }
    public interface Executor { String execute(String name, JSONObject args) throws Exception; }
    public interface Access { boolean allowed(Definition tool); }
    public interface Approval { boolean confirm(Definition tool, JSONObject args) throws Exception; }
    public interface Journal {
        String started(String name, JSONObject args);
        void completed(String id, String state, String detail);
    }
    private final Map<String, Definition> catalog = new LinkedHashMap<>();
    private final TaskSession session;
    private final Access access;
    private final Approval approval;
    private final Executor executor;
    private final Journal journal;
    private final java.util.Set<String> attemptedWrites=new java.util.HashSet<>();
    private static String canonical(Object value) {
        if(value instanceof JSONObject){java.util.TreeMap<String,String> sorted=new java.util.TreeMap<>();
            JSONObject object=(JSONObject)value;for(String key:UnifiedAgentStore.keys(object))sorted.put(key,canonical(object.opt(key)));return sorted.toString();}
        if(value instanceof JSONArray){StringBuilder out=new StringBuilder("[");JSONArray array=(JSONArray)value;
            for(int i=0;i<array.length();i++)out.append(canonical(array.opt(i))).append(',');return out.append(']').toString();}
        return String.valueOf(value);
    }
    // Serializes writes across tasks for the same account/project, including separate broker instances.
    private static final java.util.concurrent.ConcurrentMap<String, Object> WRITE_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();
    public ToolBroker(TaskSession session, Access access, Approval approval, Executor executor, Journal journal) {
        this.session=session; this.access=access; this.approval=approval; this.executor=executor; this.journal=journal;
    }
    public void register(Definition definition) {
        if (catalog.putIfAbsent(definition.name, definition) != null) throw new IllegalArgumentException("Инструмент уже зарегистрирован");
    }
    /** Host-written records survive restarts; unknown writes stay blocked until reconciled. */
    public void restoreMutationLedger(JSONArray history) {
        for(int i=0;i<history.length();i++){
            JSONObject record=history.optJSONObject(i);if(record==null)continue;
            JSONObject pending=record.optJSONObject("pendingCall");if(pending!=null)rememberWrite(pending);
            JSONArray results=record.optJSONArray("results");
            if(results!=null)for(int j=0;j<results.length();j++){
                JSONObject item=results.optJSONObject(j);if(item!=null)rememberWrite(item);
            }
        }
    }
    private void rememberWrite(JSONObject call) {
        Definition definition=catalog.get(call.optString("name"));JSONObject args=call.optJSONObject("args");
        if(definition!=null&&definition.operation==Operation.WRITE&&args!=null)
            attemptedWrites.add(definition.name+":"+canonical(args));
    }
    public JSONArray schemas() throws org.json.JSONException {
        JSONArray result = new JSONArray();
        for (Definition d : catalog.values()) if (access.allowed(d) && (!d.projectRequired || !session.projectId.isEmpty()))
            result.put(new JSONObject().put("name",d.name).put("description",d.description).put("parameters",d.schema()));
        return result;
    }
    public String execute(String name, JSONObject arguments) throws Exception {
        session.check();
        Definition tool = catalog.get(name);
        if (tool == null) throw new SecurityException("Инструмент не зарегистрирован: " + name);
        JSONObject args = UnifiedAgentStore.copy(arguments);
        String id = journal.started(name, redact(args));
        try {
            JsonSchema.validate(tool.schema(), args);
            if (args.toString().length()>300000) throw new IllegalArgumentException("Слишком большие аргументы");
            checkAccess(tool);
            if (tool.operation == Operation.WRITE) {
                Object lock = WRITE_LOCKS.computeIfAbsent(session.accountKey+":"+session.projectId, key -> new Object());
                synchronized (lock) {
                    String key=name+":"+canonical(args);
                    if(!attemptedWrites.add(key))throw new SecurityException("Повтор изменения заблокирован; сначала проверьте фактическое состояние");
                    return dispatch(tool,args,id);
                }
            }
            return dispatch(tool,args,id);
        } catch (java.util.concurrent.CancellationException | InterruptedException cancelled) {
            journal.completed(id,"cancelled","Операция отменена"); throw cancelled;
        } catch (Exception failed) {
            journal.completed(id,"failed",AgentLogRedactor.redact(failed.getMessage())); throw failed;
        }
    }
    private void checkAccess(Definition tool) {
        session.check();
        if (!access.allowed(tool) || (tool.projectRequired && session.projectId.isEmpty()))
            throw new SecurityException("Нет доступа к инструменту: " + tool.name);
    }
    private String dispatch(Definition tool, JSONObject args, String id) throws Exception {
        checkAccess(tool);
        if (tool.sensitive && !approval.confirm(tool, UnifiedAgentStore.copy(args))) {
            throw new java.util.concurrent.CancellationException("Подтверждение отклонено");
        }
        checkAccess(tool);
        String result=executor.execute(tool.name,args);
        // Record before the post-call context check: a mutation may already have happened.
        boolean cancelled=false;
        try{cancelled=new JSONObject(result==null?"{}":result).optBoolean("cancelled");}catch(org.json.JSONException prose){}
        journal.completed(id,cancelled?"cancelled":"succeeded",AgentLogRedactor.redact(result == null ? "" : result));
        if(cancelled)throw new java.util.concurrent.CancellationException("Операция отклонена");
        return result;
    }
    private static JSONObject redact(JSONObject value) {
        try { return new JSONObject(AgentLogRedactor.redact(value.toString())); }
        catch (Exception malformed) { return new JSONObject(); }
    }
}
