package app.githubble.mobile.agent;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.junit.Test;

public class AgentTextStreamTest {
  @Test
  public void chatStreamAssemblesTextAndUsage() throws Exception {
    String wire =
        "data: {\"choices\":[{\"delta\":{\"content\":\"При\"}}]}\n\n"
            + "data: {\"choices\":[{\"delta\":{\"content\":\"вет\"}}]}\n\n"
            + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2}}\n\n"
            + "data: [DONE]\n\n";
    ArrayList<String> text = new ArrayList<>();
    long[] usage = {0};
    assertEquals(
        "Привет",
        AgentTextStream.read(
            new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)),
            text::add,
            j -> usage[0] = j.optJSONObject("usage").optLong("prompt_tokens")));
    assertEquals("При", text.get(0));
    assertEquals("Привет", text.get(1));
    assertEquals(4, usage[0]);
  }

  @Test
  public void responsesIgnoresReasoningAndToolArguments() throws Exception {
    String wire =
        "data: {\"type\":\"response.function_call_arguments.delta\",\"delta\":\"secret\"}\n\n"
            + "data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"hidden\"}\n\n"
            + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Ответ\"}\n\n";
    ArrayList<String> text = new ArrayList<>();
    assertEquals(
        "Ответ",
        AgentTextStream.read(
            new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)), text::add, j -> {}));
    assertEquals(1, text.size());
  }

  @Test(expected = java.io.IOException.class)
  public void providerFailureIsNotACompleteReply() throws Exception {
    String wire = "data: {\"type\":\"response.failed\",\"response\":{}}\n\n";
    AgentTextStream.read(
        new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)), v -> {}, v -> {});
  }
}
