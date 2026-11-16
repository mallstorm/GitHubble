package app.githubble.mobile.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class ModelPicksTest {
    @Test public void keyRoundTrip() {
        String key = ModelPicks.key("openrouter", "vendor/model:free");
        assertEquals("openrouter", ModelPicks.providerOf(key));
        assertEquals("vendor/model:free", ModelPicks.modelOf(key));
    }

    @Test public void toggleAddsThenRemoves() {
        List<String> one = ModelPicks.toggle(Collections.<String>emptyList(), "a|m1");
        assertEquals(Arrays.asList("a|m1"), one);
        List<String> two = ModelPicks.toggle(one, "b|m2");
        assertEquals(Arrays.asList("b|m2", "a|m1"), two);
        assertEquals(Arrays.asList("b|m2"), ModelPicks.toggle(two, "a|m1"));
    }

    @Test public void pushKeepsNewestFirstAndLimit() {
        List<String> list = Collections.emptyList();
        for (int i = 0; i < 8; i++) list = ModelPicks.push(list, "p|m" + i, 5);
        assertEquals(5, list.size());
        assertEquals("p|m7", list.get(0));
        list = ModelPicks.push(list, "p|m5", 5);
        assertEquals("p|m5", list.get(0));
        assertEquals(5, list.size());
    }

    @Test public void parseIgnoresDamagedAndDuplicates() {
        assertTrue(ModelPicks.parse("garbage").isEmpty());
        assertEquals(Arrays.asList("a|b"), ModelPicks.parse("[\"a|b\",\"a|b\",\"nobar\"]"));
        assertEquals("[\"a|b\"]", ModelPicks.serialize(Arrays.asList("a|b")));
    }
}
