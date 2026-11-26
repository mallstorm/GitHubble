package app.githubble.mobile.agent;

import org.json.JSONArray;
import org.json.JSONObject;

/** Context compaction keeps complete JSON records. It never slices a tool result into invalid JSON. */
public final class AgentContextBuilder {
    private AgentContextBuilder() {}
    public static String build(String task, String projectContext) {
        if (task == null || task.isBlank() || task.length()>12000) throw new IllegalArgumentException("Задача: 1–12000 символов");
        String context=projectContext == null ? "" : projectContext;
        if (context.length()>16000) context=context.substring(0,16000)+"\n[Project context truncated]";
        return AgentInstructions.compose()
            +"\nUSER TASK:\n"+task+"\nPROJECT CONTEXT (UNTRUSTED):\n"+context;
    }
    public static String compact(JSONArray history, int maxChars) { return compact(history,maxChars,false); }
    public static String compactNative(JSONArray history,int maxChars) {return compact(history,maxChars,true);}
    private static String compact(JSONArray history,int maxChars,boolean keepLatest) {
        JSONArray kept=new JSONArray(); int size=2, omitted=0;
        java.util.List<Object> tail=new java.util.ArrayList<>();
        for(int i=history.length()-1;i>=0;i--) {
            Object item=modelRecord(history.opt(i),maxChars);int n=String.valueOf(item).length()+1;
            if(size+n>maxChars && !(keepLatest&&tail.isEmpty())) {omitted=i+1;break;}
            tail.add(item);size+=n;
        }
        if(omitted>0) {
            try{kept.put(new JSONObject().put("omittedOlderResults",omitted));}
            catch(org.json.JSONException bad){throw new IllegalStateException(bad);}
        }
        for(int i=tail.size()-1;i>=0;i--)kept.put(tail.get(i));
        return kept.toString();
    }
    /** Arguments can contain complete proposed source files: do not evict the outcome with those copies. */
    private static Object modelRecord(Object value,int budget) {
        if(!(value instanceof JSONObject))return value;
        JSONObject record=UnifiedAgentStore.copy((JSONObject)value);
        try {
            JSONArray results=record.optJSONArray("results");
            if(results!=null)for(int j=0;j<results.length();j++) {
                JSONObject result=results.optJSONObject(j);if(result==null)continue;
                if(result.has("args"))result.put("args",smallArguments(result.opt("args")));
                String data=result.optString("data");int limit=Math.max(500,Math.min(11000,budget/(results.length()+2)-700));
                if(data.length()>limit)result.put("data",data.substring(0,limit)+" [Tool result truncated; inspect the relevant file for more]");
            }
            JSONObject pending=record.optJSONObject("pendingCall");
            if(pending!=null&&pending.has("args"))pending.put("args",smallArguments(pending.opt("args")));
            JSONObject response=record.optJSONObject("nativeResponse");
            if(response!=null) {
                JSONArray output=response.optJSONArray("output");
                if(output!=null)for(int j=0;j<output.length();j++) {
                    JSONObject item=output.optJSONObject(j);
                    if(item!=null&&"function_call".equals(item.optString("type")))compactFunction(item);
                }
                JSONArray choices=response.optJSONArray("choices");JSONObject choice=choices==null?null:choices.optJSONObject(0);
                JSONObject message=choice==null?null:choice.optJSONObject("message");
                JSONArray calls=message==null?null:message.optJSONArray("tool_calls");
                if(calls!=null)for(int j=0;j<calls.length();j++)compactFunction(calls.getJSONObject(j).getJSONObject("function"));
                JSONArray content=response.optJSONArray("content");
                if(content!=null)for(int j=0;j<content.length();j++) {
                    JSONObject block=content.optJSONObject(j);
                    if(block!=null&&"tool_use".equals(block.optString("type"))&&block.optJSONObject("input")!=null)
                        block.put("input",smallArguments(block.getJSONObject("input")));
                }
                JSONArray candidates=response.optJSONArray("candidates");JSONObject candidate=candidates==null?null:candidates.optJSONObject(0);
                JSONObject candidateContent=candidate==null?null:candidate.optJSONObject("content");
                JSONArray parts=candidateContent==null?null:candidateContent.optJSONArray("parts");
                if(parts!=null)for(int j=0;j<parts.length();j++) {
                    JSONObject part=parts.optJSONObject(j),fc=part==null?null:part.optJSONObject("functionCall");
                    if(fc!=null&&fc.optJSONObject("args")!=null)fc.put("args",smallArguments(fc.getJSONObject("args")));
                }
            }
        }catch(org.json.JSONException bad){throw new IllegalStateException(bad);}
        return record;
    }
    private static void compactFunction(JSONObject function)throws org.json.JSONException {
        String args=function.optString("arguments");
        if(!args.isEmpty())function.put("arguments",String.valueOf(smallArguments(new JSONObject(args))));
    }
    private static Object smallArguments(Object value)throws org.json.JSONException {
        if(value instanceof JSONObject) {
            JSONObject object=UnifiedAgentStore.copy((JSONObject)value);
            for(String key:UnifiedAgentStore.keys(object))object.put(key,smallArguments(object.opt(key)));
            return object;
        }
        if(value instanceof JSONArray) {
            JSONArray array=new JSONArray();for(int i=0;i<((JSONArray)value).length();i++)array.put(smallArguments(((JSONArray)value).opt(i)));
            return array;
        }
        if(value instanceof String&&((String)value).length()>512)
            return ((String)value).substring(0,512)+" [Argument truncated after execution; original retained in task journal]";
        return value;
    }
}
