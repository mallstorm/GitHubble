package app.githubble.mobile;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs against the actual minified Release. No test-only keep rules or app-class references. */
public final class R8SmokeInstrumentation extends Instrumentation {
  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    Activity activity = null;
    int code = 0;
    try {
      stage("Launch minified MainActivity");
      seedMarkdown();
      Intent launch =
          new Intent()
              .setClassName(
                  getTargetContext().getPackageName(), "app.githubble.mobile.MainActivity")
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
      activity = startActivitySync(launch);
      waitForIdleSync();
      final Activity app = activity;
      assertVisible(app, "Главная");
      click(app, "Настройки");
      assertVisible(app, "Hubble Agent и Workspace");
      click(app, "Чат");
      assertVisible(app, "Hubble Agent");
      assertVisible(app, "Проверка Markdown");
      assertVisible(app, "Результат");
      runOnMainSync(()->{if(find(app.getWindow().getDecorView(),"План")!=null)throw new AssertionError("Plan control remains in Release");});
      stage("Minified UI passed; starting native VLESS roundtrip");
      verifyVlessRoundTrip();
      result.putString(
          "result", "PASS: minified Release UI + JNI + SOCKS -> VLESS -> loopback echo");

    } catch (Throwable error) {
      android.util.Log.e("AndroidRuntime", "R8 Release smoke failed", error);
      Throwable cause = error;
      while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
      StackTraceElement[] frames = cause.getStackTrace();
      result.putString("result", "FAIL: " + cause + " at " + Arrays.toString(frames));
      code = -1;
    } finally {
      if (activity != null) {
        final Activity app = activity;
        runOnMainSync(app::finish);
      }
    }
    finish(code, result);
  }

  private void stage(String message) {
    Bundle status = new Bundle();
    status.putString("stage", message);
    sendStatus(1, status);
  }

  /** Seed the persisted user-data contract without referencing renamed application classes. */
  private void seedMarkdown() throws Exception {
    String thread="r8-markdown-fixture";
    String prefix="account_"+android.util.Base64.encodeToString("guest".getBytes(StandardCharsets.UTF_8),android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING)+"_";
    String source="# Проверка Markdown\n\n| Раздел | Результат |\n| --- | --- |\n| R8 | Сохранён |\n\n- [x] Таблица\n\n```java\nreturn true;\n```\n";
    JSONArray messages=new JSONArray().put(new JSONObject().put("role","agent").put("body",source).put("at",1));
    getTargetContext().getSharedPreferences("githubble_ai_workspace_v1",0).edit()
        .putString(prefix+"active_thread",thread)
        .putString(prefix+"thread_index",new JSONArray().put(new JSONObject().put("id",thread).put("title","Новый диалог").put("updated",1)).toString())
        .putString(prefix+"thread_"+thread,messages.toString()).commit();
  }

  private void assertVisible(Activity app, String text) throws Exception {
    for (int i = 0; i < 60; i++) {
      final boolean[] found = {false};
      runOnMainSync(() -> found[0] = find(app.getWindow().getDecorView(), text) != null);
      if (found[0]) return;
      Thread.sleep(100);
    }
    throw new AssertionError("Minified UI missing: " + text);
  }

  private void click(Activity app, String text) throws Exception {
    assertVisible(app, text);
    runOnMainSync(
        () -> {
          View node = find(app.getWindow().getDecorView(), text);
          while (node != null && !node.isClickable())
            node = node.getParent() instanceof View ? (View) node.getParent() : null;
          if (node == null || !node.performClick())
            throw new AssertionError("Cannot click: " + text);
        });
    waitForIdleSync();
  }

  private static View find(View view, String text) {
    if (view.getVisibility() != View.VISIBLE) return null;
    if (view instanceof TextView && text.equals(((TextView) view).getText().toString()))
      return view;
    if (view.getContentDescription() != null
        && text.equals(view.getContentDescription().toString())) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View hit = find(group.getChildAt(i), text);
        if (hit != null) return hit;
      }
    }
    return null;
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      return socket.getLocalPort();
    }
  }

  private static JSONObject stream() throws Exception {
    return new JSONObject().put("network", "tcp").put("security", "none");
  }

  private static JSONObject config(int socks, int vless, int echo, String id) throws Exception {
    JSONObject inboundSocks =
        new JSONObject()
            .put("tag", "test-socks")
            .put("listen", "127.0.0.1")
            .put("port", socks)
            .put("protocol", "socks")
            .put("settings", new JSONObject().put("auth", "noauth").put("udp", false));
    JSONObject inboundVless =
        new JSONObject()
            .put("tag", "test-vless")
            .put("listen", "127.0.0.1")
            .put("port", vless)
            .put("protocol", "vless")
            .put(
                "settings",
                new JSONObject()
                    .put("decryption", "none")
                    .put("clients", new JSONArray().put(new JSONObject().put("id", id))))
            .put("streamSettings", stream());
    JSONObject outboundVless =
        new JSONObject()
            .put("tag", "via-vless")
            .put("protocol", "vless")
            .put(
                "settings",
                new JSONObject()
                    .put(
                        "vnext",
                        new JSONArray()
                            .put(
                                new JSONObject()
                                    .put("address", "127.0.0.1")
                                    .put("port", vless)
                                    .put(
                                        "users",
                                        new JSONArray()
                                            .put(
                                                new JSONObject()
                                                    .put("id", id)
                                                    .put("encryption", "none"))))))
            .put("streamSettings", stream());
    JSONArray rules =
        new JSONArray()
            .put(
                new JSONObject()
                    .put("type", "field")
                    .put("inboundTag", new JSONArray().put("test-socks"))
                    .put("outboundTag", "via-vless"))
            .put(
                new JSONObject()
                    .put("type", "field")
                    .put("inboundTag", new JSONArray().put("test-vless"))
                    .put("outboundTag", "direct"));
    return new JSONObject()
        .put("log", new JSONObject().put("loglevel", "error"))
        .put("inbounds", new JSONArray().put(inboundSocks).put(inboundVless))
        .put(
            "outbounds",
            new JSONArray()
                .put(outboundVless)
                .put(
                    new JSONObject()
                        .put("tag", "direct")
                        .put("protocol", "freedom")
                        .put(
                            "settings",
                            new JSONObject()
                                .put(
                                    "finalRules",
                                    new JSONArray()
                                        .put(
                                            new JSONObject()
                                                .put("action", "allow")
                                                .put("ip", new JSONArray().put("127.0.0.1/32"))
                                                .put("port", String.valueOf(echo)))))))
        .put("routing", new JSONObject().put("rules", rules));
  }

  private static Socket connect(int port) throws Exception {
    Exception last = null;
    for (int attempt = 0; attempt < 100; attempt++) {
      Socket socket = new Socket();
      try {
        socket.connect(new InetSocketAddress("127.0.0.1", port), 250);
        return socket;
      } catch (Exception notReady) {
        last = notReady;
        socket.close();
        Thread.sleep(50);
      }
    }
    throw new AssertionError("Xray SOCKS listener did not become ready", last);
  }

  private void verifyVlessRoundTrip() throws Exception {
    Class<?> api = Class.forName("libXray.LibXray");
    String version = String.valueOf(api.getMethod("xrayVersion").invoke(null));
    if (version.isEmpty() || "null".equals(version))
      throw new AssertionError("JNI version unavailable");
    Method stop = api.getMethod("stopXray");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    byte[] payload = "GitHubble-minified-VLESS-roundtrip".getBytes(StandardCharsets.UTF_8);
    try (ServerSocket echo = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      echo.setSoTimeout(30000);
      int socks = freePort(), vless = freePort();
      while (vless == socks) vless = freePort();
      String request =
          String.valueOf(
              api.getMethod("newXrayRunFromJSONRequest", String.class, String.class)
                  .invoke(
                      null,
                      "",
                      config(socks, vless, echo.getLocalPort(), UUID.randomUUID().toString())
                          .toString()));
      api.getMethod("runXrayFromJSON", String.class).invoke(null, request);
      Method state = api.getMethod("getXrayState");
      for (int i = 0; i < 100 && !Boolean.TRUE.equals(state.invoke(null)); i++) Thread.sleep(50);
      if (!Boolean.TRUE.equals(state.invoke(null)))
        throw new AssertionError("Native Xray did not start");
      stage("Native core ready; starting echo server and SOCKS negotiation");
      // Start the accept timeout after native startup, which can be slow on a cold emulator.
      Future<?> echoed =
          executor.submit(
              () -> {
                try (Socket socket = echo.accept()) {
                  socket.setSoTimeout(30000);
                  byte[] data = new byte[payload.length];
                  new DataInputStream(socket.getInputStream()).readFully(data);
                  if (!Arrays.equals(payload, data))
                    throw new AssertionError("Echo request corrupted");
                  socket.getOutputStream().write(data);
                } catch (Exception error) {
                  throw new RuntimeException(error);
                }
              });
      try (Socket tunnel = connect(socks)) {
        tunnel.setSoTimeout(30000);
        OutputStream out = tunnel.getOutputStream();
        DataInputStream in = new DataInputStream(tunnel.getInputStream());
        out.write(new byte[] {5, 1, 0});
        out.flush();
        if (in.readUnsignedByte() != 5 || in.readUnsignedByte() != 0)
          throw new AssertionError("SOCKS negotiation failed");
        stage("SOCKS negotiated; opening VLESS route to echo");
        int port = echo.getLocalPort();
        out.write(new byte[] {5, 1, 0, 1, 127, 0, 0, 1, (byte) (port >> 8), (byte) port});
        out.flush();
        if (in.readUnsignedByte() != 5 || in.readUnsignedByte() != 0)
          throw new AssertionError("VLESS route failed");
        in.readUnsignedByte();
        int type = in.readUnsignedByte();
        int length = type == 1 ? 4 : type == 4 ? 16 : type == 3 ? in.readUnsignedByte() : -1;
        if (length < 0) throw new AssertionError("Invalid SOCKS address");
        byte[] bound = new byte[length + 2];
        in.readFully(bound);
        stage("VLESS route open; checking echo payload");
        out.write(payload);
        out.flush();
        byte[] received = new byte[payload.length];
        in.readFully(received);
        if (!Arrays.equals(payload, received))
          throw new AssertionError("VLESS roundtrip corrupted");
      }
      echoed.get(10, TimeUnit.SECONDS);
    } finally {
      try {
        stop.invoke(null);
      } finally {
        executor.shutdownNow();
      }
    }
  }
}
