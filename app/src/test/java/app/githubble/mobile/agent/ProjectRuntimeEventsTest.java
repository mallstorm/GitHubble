package app.githubble.mobile.agent;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
public class ProjectRuntimeEventsTest {
    @Test public void eventsBracketActualToolExecutionAndKeepTarget()throws Exception{
        ArrayList<String> events=new ArrayList<>();AtomicInteger rounds=new AtomicInteger();
        ProjectRuntime.ToolHost host=new ProjectRuntime.ToolHost(){
            public String catalog(){return "read_file {path}";}
            public boolean handles(String name){return name.equals("read_file");}
            public String execute(String name,JSONObject args){events.add("execute:"+args.optString("path"));return "source";}
        };
        String answer=ProjectRuntime.analyze(prompt->rounds.incrementAndGet()==1?"{\"tools\":[{\"name\":\"read_file\",\"args\":{\"path\":\"src/App.java\"}}]}":"{\"answer\":\"ok\"}",
            null,null,"owner","","main","","Inspect","",false,false,()->{},host,new ProjectRuntime.Listener(){
                public String started(String name,JSONObject args){events.add("start:"+args.optString("path"));return "id";}
                public void completed(String id,String state,String error){events.add(id+":"+state);}
            });
        assertEquals("ok",answer);assertEquals(java.util.Arrays.asList("start:src/App.java","execute:src/App.java","id:succeeded"),events);
    }
    @Test public void capabilitiesAreRefreshedAfterWorkspaceAttachment()throws Exception{
        boolean[] attached={false};AtomicInteger rounds=new AtomicInteger();
        ProjectRuntime.ToolHost host=new ProjectRuntime.ToolHost(){
            public String catalog(){return attached[0]?"LOCAL WORKSPACE READY: read_file":"IMPORT AVAILABLE: import_repository";}
            public boolean handles(String n){return n.equals("import_repository")||attached[0]&&n.equals("read_file");}
            public String execute(String n,JSONObject a){attached[0]=true;return "ok";}
        };
        ProjectRuntime.analyze(prompt->{if(rounds.incrementAndGet()==1)return "{\"tools\":[{\"name\":\"import_repository\",\"args\":{}}]}";
            assertTrue(prompt.contains("CURRENT HOST CAPABILITIES:\nLOCAL WORKSPACE READY"));return "Done";},null,null,"owner","","main","","Inspect","",false,false,()->{},host);
        assertEquals(2,rounds.get());
    }
    @Test public void cancellationIsReportedAndStopsTheLoop()throws Exception{
        ArrayList<String> states=new ArrayList<>();AtomicInteger completions=new AtomicInteger();
        ProjectRuntime.ToolHost host=new ProjectRuntime.ToolHost(){public String catalog(){return "read_file";}public boolean handles(String n){return true;}public String execute(String n,JSONObject a){throw new java.util.concurrent.CancellationException();}};
        try{ProjectRuntime.analyze(prompt->{completions.incrementAndGet();return "{\"tools\":[{\"name\":\"read_file\"}]}";},null,null,"owner","","main","","Inspect","",false,false,()->{},host,new ProjectRuntime.Listener(){public void completed(String id,String state,String error){states.add(state);}});fail("Cancelled task continued");}catch(java.util.concurrent.CancellationException expected){}
        assertEquals(1,completions.get());assertEquals(java.util.Arrays.asList("cancelled"),states);
    }
}
