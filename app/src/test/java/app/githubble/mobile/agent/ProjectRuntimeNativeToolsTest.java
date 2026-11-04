package app.githubble.mobile.agent;
import org.json.JSONObject;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
public final class ProjectRuntimeNativeToolsTest {
    @Test public void modelCanDiscussThenInvokeHostAndUseActualResult()throws Exception{
        AtomicInteger modelCalls=new AtomicInteger(),writes=new AtomicInteger();
        String result=ProjectRuntime.analyze(prompt->{
            if(modelCalls.getAndIncrement()==0){assertTrue(prompt.contains("create_repository"));return "{\"tools\":[{\"name\":\"create_repository\",\"args\":{\"name\":\"demo\"}}]}";}
            assertTrue(prompt.contains("verified-id-42"));return "{\"answer\":\"Repository created\"}";
        },null,null,"owner","","main","","Create demo","",false,false,null,new ProjectRuntime.ToolHost(){
            public String catalog(){return "create_repository {name}";}
            public boolean handles(String name){return "create_repository".equals(name);}
            public String execute(String name,JSONObject args)throws Exception{writes.incrementAndGet();assertEquals("demo",args.getString("name"));return "verified-id-42";}
        });
        assertEquals("Repository created",result);assertEquals(1,writes.get());
    }
    @Test public void ordinaryReasoningNeverInvokesHost()throws Exception{
        String result=ProjectRuntime.analyze(prompt->"Let's compare designs",null,null,"owner","","main","",
            "Discuss architecture","",false,false,null,new ProjectRuntime.ToolHost(){
                public String catalog(){return "PLANNING: read only";}
                public boolean handles(String n){return false;}
                public String execute(String n,JSONObject a){throw new AssertionError("No tool expected");}
            });
        assertEquals("Let's compare designs",result);
    }
    @Test(expected=SecurityException.class) public void unadvertisedWriteCannotRun()throws Exception{
        ProjectRuntime.analyze(p->"{\"tools\":[{\"name\":\"create_repository\"}]}",null,null,"owner","","main","",
            "Plan only","",false,false,null,new ProjectRuntime.ToolHost(){
                public String catalog(){return "PLANNING";}
                public boolean handles(String n){return false;}
                public String execute(String n,JSONObject a){throw new AssertionError();}
            });
    }
}
