package app.githubble.mobile.agent;

import org.junit.Test;
import static org.junit.Assert.*;

public final class GatewayPathQueryTest {
    @Test public void danglingQuestionMarkIsDropped(){
        assertEquals("/repos/o/r/actions/workflows/build.yml/runs",GatewayPolicy.normalize("/repos/o/r/actions/workflows/build.yml/runs?"));
    }
    @Test public void spacesAndRussianTextInTheQueryAreEncoded(){
        assertEquals("/repos/o/r/actions/runs?branch=a%20b",GatewayPolicy.normalize("/repos/o/r/actions/runs?branch=a b"));
        assertNotNull(GatewayPolicy.normalize("/repos/o/r/issues?q=ошибка"));
    }
    @Test public void pathStaysStrict(){
        assertNull(GatewayPolicy.normalize("/repos/o/r/../x"));
        assertNull(GatewayPolicy.normalize("/repos/o/ r"));
        assertNull(GatewayPolicy.normalize("/repos//o"));
    }
}
