package app.githubble.mobile.agent;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Text-only Chat Completions/Responses SSE. Tool arguments and reasoning are never previewed. */
public final class AgentTextStream {
  private AgentTextStream() {}

  public static String read(InputStream input, Consumer<String> text, Consumer<JSONObject> usage)
      throws Exception {
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    StringBuilder data = new StringBuilder(), answer = new StringBuilder();
    String line;
    int total = 0;
    while ((line = reader.readLine()) != null) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Отменено");
      total += line.length();
      if (total > 2 * 1024 * 1024) throw new java.io.IOException("Поток ответа превышает лимит");
      if (line.isEmpty()) {
        if (consume(data.toString(), answer, text, usage)) break;
        data.setLength(0);
      } else if (line.startsWith("data:")) {
        if (data.length() > 0) data.append('\n');
        data.append(line.substring(5).replaceFirst("^\\s+", ""));
      }
    }
    if (data.length() > 0) consume(data.toString(), answer, text, usage);
    return answer.toString().trim();
  }

  private static boolean consume(
      String data, StringBuilder answer, Consumer<String> text, Consumer<JSONObject> usage)
      throws Exception {
    if (data.trim().isEmpty()) return false;
    if (data.trim().equals("[DONE]")) return true;
    JSONObject event = new JSONObject(data);
    JSONObject response = event.optJSONObject("response");
    if (event.has("usage")) usage.accept(event);
    else if (response != null && response.has("usage")) usage.accept(response);
    if (event.has("error") || event.optString("type").equals("response.failed"))
      throw new java.io.IOException("Провайдер прервал ответ");
    String addition = "";
    if ("response.output_text.delta".equals(event.optString("type")))
      addition = event.optString("delta");
    else {
      JSONArray choices = event.optJSONArray("choices");
      JSONObject choice = choices == null ? null : choices.optJSONObject(0),
          delta = choice == null ? null : choice.optJSONObject("delta");
      if (delta != null) {
        Object content = delta.opt("content");
        if (content instanceof String) addition = (String) content;
      }
    }
    if (!addition.isEmpty()) {
      if (answer.length() + addition.length() > 80000)
        throw new java.io.IOException("Ответ превышает 80 000 символов");
      answer.append(addition);
      text.accept(answer.toString());
    }
    return false;
  }
}
