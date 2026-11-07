package app.githubble.mobile.agent;
import org.junit.Test;
import static org.junit.Assert.*;
public final class FileChangeStatsTest {
    private static void counts(String before,String after,int plus,int minus){
        FileChangeStats s=FileChangeStats.between(before,after);
        assertEquals(plus,s.added);assertEquals(minus,s.removed);assertFalse(s.approximate);
    }
    @Test public void creationInsertionReplacementAndRemoval(){
        counts(null,"a\nb\n",2,0);counts("a\nb\n","a\nx\nb\n",1,0);
        counts("a\nb\nc\n","a\nx\nc\n",1,1);counts("a\nb\n","b\n",0,1);
        counts("a\nb\nc\n","a\nx\nb\ny\n",2,1);
    }
    @Test public void zeroAndLineEndings(){counts("a\n","a\n",0,0);counts("a\r\nb\r\n","a\nb\n",0,0);counts("","",0,0);}
    @Test public void expensiveDiffIsMarkedApproximate(){
        FileChangeStats s=FileChangeStats.between("a\n".repeat(2100),"b\n".repeat(2100));
        assertTrue(s.approximate);assertEquals(2100,s.added);assertEquals(2100,s.removed);
    }
}
