package app.githubble.mobile.agent;

import org.json.JSONObject;

/** Persist reservations before dispatch so restarts and failed calls cannot reset the task's budget. */
public final class AgentBudget {
    public static final int TOOLS_PER_TURN=8;
    public static final class Exhausted extends Exception { public Exhausted(String detail) { super(detail); } }
    private final JSONObject value;
    private long reservedInput;
    private final java.util.function.Consumer<JSONObject> persist;
    public AgentBudget(JSONObject value, java.util.function.Consumer<JSONObject> persist) {
        this.value=UnifiedAgentStore.copy(value); this.persist=persist;
    }
    public synchronized void round(long estimatedInput) throws Exhausted, org.json.JSONException {
        if (value.optInt("rounds")>=Math.min(value.optInt("roundWindowStart")+25,value.optInt("maxRounds",12))) throw new Exhausted("Исчерпан бюджет раундов");
        long used=value.optLong("inputTokens")+value.optLong("outputTokens");
        if (used+estimatedInput+8192>value.optLong("maxTokens",180000)) throw new Exhausted("Исчерпан бюджет токенов");
        if (value.optLong("maxCostMicros",-1)>=0 && value.optLong("costMicros")>=value.optLong("maxCostMicros"))
            throw new Exhausted("Исчерпан бюджет расходов");
        long maximumCost=value.optLong("maxCostMicros",-1);
        if(maximumCost>=0){
            if(!value.has("inputMicrosPerMillion")||!value.has("outputMicrosPerMillion"))
                throw new Exhausted("Для денежного лимита нужно настроить цену выбранной модели");
            long reservation=(long)Math.ceil((estimatedInput*value.optDouble("inputMicrosPerMillion")
                +8192*value.optDouble("outputMicrosPerMillion"))/1000000.0);
            if(reservation<0||reservation>maximumCost-value.optLong("costMicros"))throw new Exhausted("Недостаточно бюджета расходов");
            value.put("costMicros",value.optLong("costMicros")+reservation).put("costStatus","RESERVED_ESTIMATE");
        }else value.put("costStatus","UNPRICED");
        reservedInput=estimatedInput;
        value.put("rounds",value.optInt("rounds")+1).put("inputTokens",value.optLong("inputTokens")+estimatedInput);
        persist.accept(UnifiedAgentStore.copy(value));
    }
    public synchronized void output(long input,long tokens) throws org.json.JSONException,Exhausted {
        value.put("inputTokens",value.optLong("inputTokens")+Math.max(0,input-reservedInput));
        value.put("outputTokens",value.optLong("outputTokens")+tokens); persist.accept(UnifiedAgentStore.copy(value));
        if(value.optLong("inputTokens")+value.optLong("outputTokens")>value.optLong("maxTokens",180000))
            throw new Exhausted("Ответ модели превысил оставшийся бюджет токенов");
    }
    public synchronized void tool() throws Exhausted, org.json.JSONException {
        if (value.optInt("tools")>=Math.min(value.optInt("toolWindowStart")+100,value.optInt("maxTools",100))) throw new Exhausted("Исчерпан бюджет инструментов");
        value.put("tools",value.optInt("tools")+1); persist.accept(UnifiedAgentStore.copy(value));
    }
    public synchronized int rounds() { return value.optInt("rounds"); }
}
