package app.githubble.mobile.network;

import java.util.List;
import kotlin.Pair;
import org.junit.Test;
import static org.junit.Assert.*;

public final class GigaChatBalanceTest {
    @Test public void balanceListIsParsedAndFormattedBiggestFirst(){
        List<Pair<String,Long>> items=GigaChatBalance.parse("{\"balance\":[{\"usage\":\"GigaChat-Pro\",\"value\":250000},{\"usage\":\"GigaChat\",\"value\":1000000},{\"usage\":\"\",\"value\":5}]}");
        assertEquals(2,items.size());
        assertEquals("GigaChat: 1 000 000 токенов\nGigaChat-Pro: 250 000 токенов",GigaChatBalance.format(items));
    }
    @Test public void damagedOrEmptyBodiesGiveNoItems(){
        assertTrue(GigaChatBalance.parse("").isEmpty());
        assertTrue(GigaChatBalance.parse("not json").isEmpty());
        assertEquals("Сервис не вернул остаток токенов",GigaChatBalance.format(GigaChatBalance.parse("{}")));
    }
}
