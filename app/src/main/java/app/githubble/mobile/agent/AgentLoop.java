package app.githubble.mobile.agent;

import org.json.JSONArray;
import org.json.JSONObject;

/** One loop for native and compatible tools. Mutations are dispatched once and never auto-retried. */
public final class AgentLoop {
    public static final class Outcome {
        public final String answer;
        public final boolean budgetExhausted;
        Outcome(String answer, boolean exhausted) { this.answer=answer; budgetExhausted=exhausted; }
    }
    public interface Checkpoint { void save(JSONArray history); }
    public Outcome run(ModelTransport model, ToolBroker broker, TaskSession session, AgentBudget budget,
            String task, String context, JSONArray restored, Checkpoint checkpoint, java.util.function.IntConsumer onRound) throws Exception {
        String base=AgentContextBuilder.build(task,context);
        JSONArray history=new JSONArray(restored.toString());
        try {
            while (true) {
                session.check(); JSONArray schemas=broker.schemas();
                String compact=AgentContextBuilder.compact(history,28000);
                checkpoint.save(history);
                budget.round((base.length()+compact.length()+schemas.toString().length()+2)/3);
                onRound.accept(budget.rounds());
                ModelTransport.Turn turn=model.complete(base,schemas,history);
                budget.output(turn.inputTokens,Math.max(turn.outputTokens,(turn.answer.length()+turn.calls.toString().length()+2)/3));
                session.check();
                if (turn.calls.length()==0) {
                    if (turn.answer.isBlank()) throw new IllegalStateException("Модель не вернула ответ");
                    return new Outcome(turn.answer,false);
                }
                // Reject the whole oversized batch before any operation. Every native call still gets a result.
                if (turn.calls.length()>AgentBudget.TOOLS_PER_TURN) {
                    JSONArray errors=new JSONArray();
                    for (int i=0;i<turn.calls.length();i++) {
                        JSONObject call=turn.calls.getJSONObject(i);
                        errors.put(new JSONObject().put("id",call.optString("id","call_"+i))
                            .put("error","At most 8 tools per response; this entire batch was NOT executed."));
                    }
                    history.put(new JSONObject().put("nativeResponse",turn.nativeResponse).put("results",errors));
                    checkpoint.save(history); continue;
                }
                JSONArray results=new JSONArray();
                for (int i=0;i<turn.calls.length();i++) {
                    session.check(); budget.tool();
                    JSONObject call=turn.calls.getJSONObject(i);
                    String name=call.getString("name"); JSONObject args=call.optJSONObject("args");
                    if (args==null) throw new IllegalArgumentException("Аргументы инструмента должны быть объектом");
                    JSONObject item=new JSONObject().put("id",call.optString("id","call_"+i)).put("name",name).put("args",args);
                    // Save the dispatch intent before executing. An interrupted mutation must be reconciled manually.
                    checkpoint.save(new JSONArray(history.toString()).put(new JSONObject().put("pendingCall",item)));
                    try {
                        String data=broker.execute(name,args);
                        item.put("data",data == null ? "" : data.length()>18000 ? data.substring(0,18000)+" [truncated]" : data);
                    } catch (java.util.concurrent.CancellationException | InterruptedException | SecurityException stop) { throw stop;
                    } catch (Exception failed) {
                        item.put("error",AgentLogRedactor.redact(failed.getMessage())).put("instruction",
                            "Do not repeat a mutation. Inspect actual state before proposing recovery.");
                    }
                    results.put(item);
                    // Keep completed operations even if the remaining batch is cancelled or runs out of budget.
                    checkpoint.save(new JSONArray(history.toString()).put(new JSONObject()
                        .put("nativeResponse",turn.nativeResponse).put("results",new JSONArray(results.toString()))
                        .put("partialBatch",i+1<turn.calls.length())));
                }
                history.put(new JSONObject().put("nativeResponse",turn.nativeResponse).put("results",results));
                checkpoint.save(history);
            }
        } catch (AgentBudget.Exhausted exhausted) {
            return new Outcome(exhausted.getMessage()+". Задача сохранена. Продолжение требует разрешения пользователя.",true);
        }
    }
}
