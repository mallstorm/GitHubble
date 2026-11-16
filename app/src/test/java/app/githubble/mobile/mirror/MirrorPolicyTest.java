package app.githubble.mobile.mirror;
import static org.junit.Assert.*;
import org.junit.Test;
public class MirrorPolicyTest {
    @Test public void acceptsRealRepositoryNames() {
        assertEquals("mallstorm/OmniCode",MirrorPolicy.requireRepository("mallstorm/OmniCode"));
        assertEquals("Digger401/OmniCode",MirrorPolicy.requireDifferent("mallstorm/OmniCode","Digger401/OmniCode"));
    }
    @Test public void refusesUnsafeOrAmbiguousInputs() {
        for(String full : new String[]{"","test","https://github.com/mallstorm/demo",
                "a/b;rm -rf", "a/../../b", "a/x y", "a/.","a/.."})
            try {MirrorPolicy.requireRepository(full); fail("Accepted "+full);}
            catch(IllegalArgumentException expected) {}
        try {MirrorPolicy.requireDifferent("mallstorm/Test","MALLSTORM/test");fail();}
        catch(IllegalArgumentException expected) {}
    }
}
