package app.githubble.mobile.agent;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ToolBrokerTest {
    @Test public void schemaAndPermissionsAreCheckedBeforeExecution() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        ToolBroker b=new ToolBroker(new TaskSession("a","project","task",()->{}),d->true,(d,a)->true,(n,a)->{calls.incrementAndGet();return "ok";},UnifiedAgentTestSupport.journal());b.register(UnifiedAgentTestSupport.read());
        for(JSONObject args:new JSONObject[]{new JSONObject(),new JSONObject().put("path",1),new JSONObject().put("path","x").put("extra",true)}){
            try{b.execute("read_file",args);fail();}catch(IllegalArgumentException expected){}
        }
        assertEquals(0,calls.get());assertEquals("ok",b.execute("read_file",new JSONObject().put("path","x")));
    }
    @Test public void sensitiveOperationNeedsOneExplicitApprovalAndRevocationIsRechecked() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean allowed=new java.util.concurrent.atomic.AtomicBoolean(true);java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        ToolBroker b=new ToolBroker(new TaskSession("a","p","t",()->{}),d->allowed.get(),(d,a)->{allowed.set(false);return true;},(n,a)->{calls.incrementAndGet();return "done";},UnifiedAgentTestSupport.journal());
        b.register(new ToolBroker.Definition("release","release",UnifiedAgentTestSupport.schema(),ToolBroker.Operation.WRITE,ToolBroker.Permission.GITHUB_WRITE,true,true));
        try{b.execute("release",new JSONObject().put("path","x"));fail();}catch(SecurityException expected){}assertEquals(0,calls.get());
    }
    @Test(expected=SecurityException.class) public void shellNeverEntersCatalog() throws Exception {
        ToolBroker b=new ToolBroker(new TaskSession("a","p","t",()->{}),d->true,(d,a)->true,(n,a)->"bad",UnifiedAgentTestSupport.journal());ToolCatalog.install(b);b.execute("shell",new JSONObject());
    }
    @Test public void cancelledScopeCannotExecute() throws Exception {
        TaskSession s=new TaskSession("a","p","t",()->{});s.cancel();
        ToolBroker b=new ToolBroker(s,d->true,(d,a)->true,(n,a)->{fail();return "";},UnifiedAgentTestSupport.journal());b.register(UnifiedAgentTestSupport.read());
        try{b.execute("read_file",new JSONObject().put("path","x"));fail();}catch(java.util.concurrent.CancellationException expected){}
    }
    @Test public void independentBrokersSerializeProjectWrites() throws Exception {
        java.util.concurrent.atomic.AtomicInteger inFlight=new java.util.concurrent.atomic.AtomicInteger(),peak=new java.util.concurrent.atomic.AtomicInteger();
        ToolBroker.Executor executor=(n,a)->{int now=inFlight.incrementAndGet();peak.accumulateAndGet(now,Math::max);Thread.sleep(20);inFlight.decrementAndGet();return "ok";};
        ToolBroker.Definition write=new ToolBroker.Definition("write","",UnifiedAgentTestSupport.schema(),ToolBroker.Operation.WRITE,ToolBroker.Permission.LOCAL_WRITE,false,true);
        ToolBroker one=new ToolBroker(new TaskSession("a","p","one",()->{}),d->true,(d,a)->true,executor,UnifiedAgentTestSupport.journal());one.register(write);
        ToolBroker two=new ToolBroker(new TaskSession("a","p","two",()->{}),d->true,(d,a)->true,executor,UnifiedAgentTestSupport.journal());two.register(write);
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{java.util.concurrent.Future<?> a=pool.submit(()->{try{one.execute("write",new JSONObject().put("path","x"));}catch(Exception e){throw new RuntimeException(e);}});
            java.util.concurrent.Future<?> b=pool.submit(()->{try{two.execute("write",new JSONObject().put("path","x"));}catch(Exception e){throw new RuntimeException(e);}});a.get();b.get();assertEquals(1,peak.get());
        }finally{pool.shutdownNow();}
    }
    @Test public void interruptedWriteIsBlockedAfterBrokerRecreation() throws Exception {
        TaskSession session=new TaskSession("a","p","t",()->{});
        ToolBroker broker=new ToolBroker(session,d->true,(d,a)->true,(n,a)->{fail("replayed uncertain mutation");return "";},UnifiedAgentTestSupport.journal());
        broker.register(new ToolBroker.Definition("write","",UnifiedAgentTestSupport.schema(),ToolBroker.Operation.WRITE,ToolBroker.Permission.LOCAL_WRITE,false,true));
        broker.restoreMutationLedger(new JSONArray().put(new JSONObject().put("pendingCall",new JSONObject().put("name","write").put("args",new JSONObject().put("path","file")))));
        try{broker.execute("write",new JSONObject().put("path","file"));fail();}catch(SecurityException expected){}
    }
}
