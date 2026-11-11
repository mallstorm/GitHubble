package app.githubble.mobile.agent.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Atomic file persistence for task journals (write temp, fsync, rename). */
public final class JournalStore {
    private final File dir;

    public JournalStore(File dir) {
        this.dir = dir;
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Не создан каталог журналов");
    }

    private File file(String taskId) {
        if (!taskId.matches("[A-Za-z0-9-]{1,64}")) throw new IllegalArgumentException("Недопустимый id задачи");
        return new File(dir, taskId + ".journal");
    }

    public void save(TaskJournal journal) throws IOException {
        File target = file(journal.id), tmp = new File(dir, journal.id + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(journal.serialize().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** @return null when no journal exists */
    public TaskJournal load(String taskId, TaskJournal.Clock clock) throws IOException {
        File f = file(taskId);
        if (!f.isFile()) return null;
        return TaskJournal.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), clock);
    }

    /** Non-terminal journals: candidates for resume after a restart (TZ 11.2). */
    public java.util.List<TaskJournal> unfinished(TaskJournal.Clock clock) throws IOException {
        java.util.List<TaskJournal> out = new java.util.ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".journal"));
        if (files == null) return out;
        for (File f : files) {
            try {
                TaskJournal j = TaskJournal.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), clock);
                if (!j.state().isTerminal()) out.add(j);
            } catch (IllegalArgumentException corrupt) { /* skipped: never resume a damaged journal */ }
        }
        return out;
    }
}
