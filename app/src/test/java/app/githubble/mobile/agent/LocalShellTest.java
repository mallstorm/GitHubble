package app.githubble.mobile.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import org.junit.Test;

public class LocalShellTest {
    @Test public void runsInsideTheGivenDirectory() throws Exception {
        File dir = Files.createTempDirectory("shell").toFile();
        new File(dir, "marker.txt").createNewFile();
        LocalShell.Result result = LocalShell.run(dir, "ls", 5000, 4096);
        assertEquals(0, result.exitCode);
        assertTrue(result.output.contains("marker.txt"));
    }

    @Test public void timeoutStopsLongCommands() throws Exception {
        File dir = Files.createTempDirectory("shell").toFile();
        LocalShell.Result result = LocalShell.run(dir, "sleep 5", 300, 4096);
        assertTrue(result.timedOut);
    }

    @Test public void outputIsCapped() throws Exception {
        File dir = Files.createTempDirectory("shell").toFile();
        LocalShell.Result result = LocalShell.run(dir, "yes x | head -c 20000", 5000, 1000);
        assertTrue(result.truncated);
        assertEquals(1000, result.output.length());
    }
}
