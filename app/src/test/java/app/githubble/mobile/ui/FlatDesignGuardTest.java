package app.githubble.mobile.ui;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Keeps the flat design: no elevation shadows in app screens. */
public class FlatDesignGuardTest {
  @Test
  public void screensDoNotUseElevationShadows() throws Exception {
    File root = new File("src/main/java/app/githubble/mobile");
    if (!root.isDirectory()) root = new File("app/src/main/java/app/githubble/mobile");
    List<String> offenders = new ArrayList<>();
    scan(root, offenders);
    assertTrue("Elevation shadows found: " + offenders, offenders.isEmpty());
  }

  private static void scan(File dir, List<String> offenders) throws Exception {
    File[] files = dir.listFiles();
    if (files == null) return;
    for (File file : files) {
      if (file.isDirectory()) scan(file, offenders);
      else if (file.getName().endsWith(".java")) {
        String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        if (source.contains(".setElevation(") && !file.getName().equals("AgentDocumentSheet.java")) offenders.add(file.getName());
      }
    }
  }
}
