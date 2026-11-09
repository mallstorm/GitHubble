package app.githubble.mobile.agent;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONObject;

/**
 * Private, bounded before/after snapshots. IDs are opaque; every read verifies account and thread.
 */
public final class AgentDiffStore {
  private final File root;

  public AgentDiffStore(Context context) {
    root = new File(context.getFilesDir(), "agent-diffs");
  }

  private File scope(String owner, String thread) throws Exception {
    byte[] hash =
        MessageDigest.getInstance("SHA-256")
            .digest((owner + "\n" + thread).getBytes(StandardCharsets.UTF_8));
    StringBuilder name = new StringBuilder();
    for (byte b : hash) name.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    return new File(root, name.toString());
  }

  public synchronized String save(
      String owner, String thread, String path, String before, String after) throws Exception {
    if ((before != null && before.length() > 100000) || (after != null && after.length() > 100000))
      return "";
    File dir = scope(owner, thread);
    if (!dir.isDirectory() && !dir.mkdirs())
      throw new java.io.IOException("Не удалось сохранить сравнение");
    String id = UUID.randomUUID().toString();
    JSONObject json =
        new JSONObject()
            .put("account", owner)
            .put("conversation", thread)
            .put("path", path)
            .put("before", before == null ? "" : before)
            .put("after", after == null ? "" : after)
            .put("created", before == null);
    AtomicFile file = new AtomicFile(new File(dir, id + ".json"));
    FileOutputStream out = null;
    try {
      out = file.startWrite();
      out.write(json.toString().getBytes(StandardCharsets.UTF_8));
      file.finishWrite(out);
    } catch (Exception error) {
      if (out != null) file.failWrite(out);
      throw error;
    }
    return id;
  }

  public synchronized JSONObject read(String owner, String thread, String id) throws Exception {
    if (id == null || !id.matches("[0-9a-f-]{36}"))
      throw new SecurityException("Некорректное сравнение");
    File f = new File(scope(owner, thread), id + ".json");
    if (f.length() > 1200000) throw new java.io.IOException("Слишком большое сравнение");
    JSONObject value =
        new JSONObject(new String(new AtomicFile(f).readFully(), StandardCharsets.UTF_8));
    if (!owner.equals(value.optString("account"))
        || !thread.equals(value.optString("conversation")))
      throw new SecurityException("Контекст сравнения изменился");
    return value;
  }
}
