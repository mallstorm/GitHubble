package app.githubble.mobile;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;
import org.junit.Test;

/** Keeps the human-readable documents in step with version.properties. */
public final class VersionConsistencyTest {
    private static File root() {
        File here = new File(".").getAbsoluteFile();
        for (File dir = here; dir != null; dir = dir.getParentFile())
            if (new File(dir, "version.properties").isFile()) return dir;
        throw new AssertionError("version.properties not found from " + here);
    }

    private static String read(File root, String path) throws Exception {
        return new String(Files.readAllBytes(new File(root, path).toPath()), StandardCharsets.UTF_8);
    }

    @Test public void readmeArchitectureAndChangelogMatchTheReleaseVersion() throws Exception {
        File root = root();
        Properties v = new Properties();
        try (FileInputStream in = new FileInputStream(new File(root, "version.properties"))) { v.load(in); }
        String name = v.getProperty("VERSION_NAME"), code = v.getProperty("VERSION_CODE");
        assertNotNull(name); assertNotNull(code);

        String readme = read(root, "README.md");
        assertTrue("README.md: Version", readme.contains("- Version: `" + name + "`"));
        assertTrue("README.md: versionCode", readme.contains("- versionCode: `" + code + "`"));
        assertTrue("README.md: channel", readme.contains("- release channel: `" + v.getProperty("RELEASE_CHANNEL") + "`"));
        assertTrue("README.md: reference line", readme.contains("`" + name + "`, versionCode `" + code + "`"));
        assertTrue("ARCHITECTURE.md", read(root, "ARCHITECTURE.md").contains("`" + name + "`"));
        assertTrue("docs/CHANGELOG.md must start with the current version",
                read(root, "docs/CHANGELOG.md").startsWith("# GitHubble " + name));
        assertTrue("release/RELEASE_NOTES_RU.md must start with the current version",
                read(root, "release/RELEASE_NOTES_RU.md").startsWith("# GitHubble " + name));
    }
}
