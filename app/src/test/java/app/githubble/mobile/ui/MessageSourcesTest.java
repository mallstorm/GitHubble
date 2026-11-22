package app.githubble.mobile.ui;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class MessageSourcesTest {
    @Test public void linksAndBareUrlsAreCollectedOnceInOrder() {
        List<MessageSources.Source> list = MessageSources.extract(
                "См. [Документация](https://docs.example.com/a#intro) и https://www.example.org/post, ещё раз [то же](https://docs.example.com/a/). "
                + "Ссылка (https://github.com/o/r) и [действие](githubble-action://continue) и http://insecure.test/x.");
        assertEquals(3, list.size());
        assertEquals("docs.example.com", list.get(0).host);
        assertEquals("Документация", list.get(0).title);
        assertEquals("example.org", list.get(1).host);
        assertEquals("https://www.example.org/post", list.get(1).url);
        assertEquals("github.com", list.get(2).host);
    }

    @Test public void noLinksNoSources() {
        assertTrue(MessageSources.extract("Просто текст без ссылок").isEmpty());
        assertTrue(MessageSources.extract(null).isEmpty());
    }

    @Test public void russianPluralsAndBadge() {
        assertEquals("1 источник", MessageSources.count(1));
        assertEquals("3 источника", MessageSources.count(3));
        assertEquals("11 источников", MessageSources.count(11));
        assertEquals("21 источник", MessageSources.count(21));
        assertEquals("30 источников", MessageSources.count(30));
        assertEquals("D", MessageSources.letter(MessageSources.extract("[x](https://docs.example.com/a)").get(0)));
    }
}
