package app.githubble.mobile.agent;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

final class UnifiedAgentTestSupport {
    static final class Memory implements UnifiedAgentStore.Persistence {
        final java.util.Map<String,String> data=new java.util.HashMap<>();
        boolean fail;
        public String read(String key){return data.getOrDefault(key,"");}
        public void write(String key,String value){if(fail)throw new IllegalStateException("disk full");data.put(key,value);}
    }
    static JSONObject schema() throws Exception {return new JSONObject("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"],\"additionalProperties\":false}");}
    static ToolBroker.Journal journal(){return new ToolBroker.Journal(){public String started(String name,JSONObject args){return "op";}public void completed(String id,String state,String detail){}};}
    static ToolBroker.Definition read() throws Exception {return new ToolBroker.Definition("read_file","read",schema(),ToolBroker.Operation.READ,ToolBroker.Permission.FILE_READ,false,true);}
    static AgentBudget budget(int rounds) throws Exception {return new AgentBudget(new JSONObject().put("maxRounds",rounds),v->{});}
}
