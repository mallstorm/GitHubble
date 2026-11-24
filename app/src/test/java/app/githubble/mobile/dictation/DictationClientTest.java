package app.githubble.mobile.dictation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class DictationClientTest {
    private static byte[] tone(int seconds, int amplitude) {
        byte[] pcm = new byte[seconds * DictationClient.RATE * 2];
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            short v = (short) (amplitude * Math.sin(i / 2 * 0.2));
            pcm[i] = (byte) v; pcm[i + 1] = (byte) (v >> 8);
        }
        return pcm;
    }

    @Test public void wavHeaderDescribesPcm() {
        byte[] wav = DictationClient.toWav(new byte[3200]);
        assertEquals(3244, wav.length);
        assertEquals("RIFF", new String(wav, 0, 4));
        assertEquals("WAVEfmt ", new String(wav, 8, 8));
        assertEquals(16000, (wav[24] & 0xff) | ((wav[25] & 0xff) << 8));
        assertEquals("data", new String(wav, 36, 4));
        assertEquals(3200, (wav[40] & 0xff) | ((wav[41] & 0xff) << 8));
    }

    @Test public void longAudioIsSplitIntoEvenChunksWithoutLoss() {
        byte[] pcm = tone(70, 8000);
        List<byte[]> parts = DictationClient.split(pcm, 28);
        int total = 0;
        for (byte[] p : parts) { assertTrue(p.length <= 28 * 32000); assertEquals(0, p.length % 2); total += p.length; }
        assertEquals(pcm.length, total);
        assertTrue(parts.size() >= 3);
        assertEquals(1, DictationClient.split(tone(5, 8000), 28).size());
        assertEquals(1, DictationClient.split(pcm, 0).size());
    }

    @Test public void splitPrefersTheQuietMoment() {
        byte[] pcm = tone(40, 9000);
        int quietFrom = 27 * 32000, quietTo = 27 * 32000 + 6400;
        for (int i = quietFrom; i < quietTo; i++) pcm[i] = 0;
        List<byte[]> parts = DictationClient.split(pcm, 28);
        int cut = parts.get(0).length;
        assertTrue("cut " + cut, cut >= quietFrom && cut <= quietTo + 3200);
    }

    @Test public void languageMapping() {
        assertEquals("", DictationClient.isoLanguage("auto"));
        assertEquals("ru", DictationClient.isoLanguage("ru-RU"));
        assertEquals("en", DictationClient.isoLanguage("en-US"));
        assertEquals("auto", DictationClient.yandexLanguage("uk-UA"));
        assertEquals("ru-RU", DictationClient.yandexLanguage("ru-RU"));
    }

    @Test public void whisperEndpointIsNormalised() {
        assertEquals("http://192.168.1.5:8000/v1/audio/transcriptions", DictationClient.whisperUrl("192.168.1.5:8000/v1/"));
        assertEquals("https://w.example.com/v1/audio/transcriptions", DictationClient.whisperUrl("https://w.example.com/v1/audio/transcriptions"));
        assertEquals("", DictationClient.whisperUrl(" "));
    }

    @Test public void googleAnswerIsJoined() throws Exception {
        assertEquals("привет мир", DictationClient.googleText("{\"results\":[{\"alternatives\":[{\"transcript\":\"привет\"}]},{\"alternatives\":[{\"transcript\":\" мир\"}]}]}"));
        assertEquals("", DictationClient.googleText("{}"));
    }

    @Test public void errorsAreExplained() {
        assertTrue(DictationClient.friendly(401, "{\"error\":{\"message\":\"Invalid API Key\"}}").contains("Ключ отклонён"));
        assertTrue(DictationClient.friendly(429, "").contains("лимит"));
        assertTrue(DictationClient.friendly(400, "{\"message\":\"bad audio\"}").contains("bad audio"));
    }

    @Test public void silenceAndNoiseLevels() {
        assertTrue(DictationClient.peak(new byte[64000]) < 250);
        assertTrue(DictationClient.peak(tone(1, 5000)) > 4000);
    }
}
