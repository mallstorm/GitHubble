package app.githubble.mobile.controller;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import app.githubble.mobile.network.GitHubApiClient;
import app.githubble.mobile.util.RepositoryPathPolicy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports Android Storage Access Framework folders/files and ZIP contents into one Git commit.
 * Files are streamed to GitHub's Git blobs API and only the small tree metadata is retained in memory.
 */
public final class RepositoryImportController {
    public static final long MAX_BLOB_BYTES = 99L * 1024L * 1024L;
    public static final int MAX_FILES = 5000;
    public static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L;
    public static final int MAX_PATH_DEPTH = 32;
    private static final String BOOTSTRAP_PATH = ".githubble-bootstrap";

    public interface ProgressListener {
        void onProgress(int completed, int total, String path);
    }

    public static final class Preview {
        public final int files;
        public final long totalBytes;
        public final int ignored;
        public final boolean hasWorkflow;
        public final List<String> samplePaths;
        public final String sourceName;

        Preview(int files, long totalBytes, int ignored, boolean hasWorkflow, List<String> samplePaths, String sourceName) {
            this.files = files;
            this.totalBytes = totalBytes;
            this.ignored = ignored;
            this.hasWorkflow = hasWorkflow;
            this.samplePaths = samplePaths;
            this.sourceName = sourceName == null ? "" : sourceName;
        }
    }

    public static final class ImportResult {
        public final String commitSha;
        public final String branch;
        public final int files;
        public final long totalBytes;
        ImportResult(String commitSha, String branch, int files, long totalBytes) {
            this.commitSha = commitSha; this.branch = branch; this.files = files; this.totalBytes = totalBytes;
        }
    }

    private static final class DocEntry {
        final Uri uri; final String path; final long size;
        DocEntry(Uri uri, String path, long size) { this.uri = uri; this.path = path; this.size = size; }
    }

    private static final class BaseState {
        final String branch; final String parentSha; final String baseTreeSha; final boolean seeded;
        BaseState(String branch, String parentSha, String baseTreeSha, boolean seeded) {
            this.branch = branch; this.parentSha = parentSha; this.baseTreeSha = baseTreeSha; this.seeded = seeded;
        }
    }

    private final Context context;
    private final ContentResolver resolver;
    private final GitHubApiClient api;

    public RepositoryImportController(Context context, GitHubApiClient api) {
        this.context = context.getApplicationContext();
        this.resolver = context.getContentResolver();
        this.api = api;
    }

    public Preview previewFolder(Uri treeUri) throws Exception {
        List<DocEntry> entries = collectFolder(treeUri);
        long bytes = 0; boolean workflow = false; List<String> samples = new ArrayList<>();
        for (DocEntry entry : entries) {
            if (entry.size > 0) bytes += entry.size;
            workflow |= RepositoryPathPolicy.isWorkflow(entry.path);
            if (samples.size() < 12) samples.add(entry.path);
        }
        return new Preview(entries.size(), bytes, 0, workflow, samples, displayName(treeUri));
    }

    public Preview previewFile(Uri uri) throws Exception {
        String name = RepositoryPathPolicy.normalize(displayName(uri));
        if (name.isEmpty()) name = "upload.bin";
        long size = querySize(uri);
        List<String> samples = new ArrayList<>(); samples.add(name);
        return new Preview(1, Math.max(0, size), 0, RepositoryPathPolicy.isWorkflow(name), samples, name);
    }

    public Preview previewZip(Uri zipUri) throws Exception {
        int files = 0, ignored = 0; long bytes = 0; boolean workflow = false; List<String> samples = new ArrayList<>(); Set<String> paths = new HashSet<>();
        try (InputStream raw = resolver.openInputStream(zipUri); ZipInputStream zip = new ZipInputStream(new BufferedInputStream(require(raw)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String path;
                try { path = RepositoryPathPolicy.normalize(entry.getName()); }
                catch (IllegalArgumentException unsafe) { ignored++; continue; }
                if (RepositoryPathPolicy.shouldIgnore(path)) { ignored++; continue; }
                validateArchivePath(path, paths);
                files++; if (files > MAX_FILES) throw new IllegalArgumentException("В архиве больше " + MAX_FILES + " файлов. Разделите импорт на несколько коммитов.");
                long size = entry.getSize(); if (size > MAX_BLOB_BYTES) throw new IllegalArgumentException("Файл больше допустимого лимита GitHub: " + path);
                long actual = drainEntry(zip, path);
                bytes += actual;
                if (bytes > MAX_TOTAL_UNCOMPRESSED_BYTES) throw new IllegalArgumentException("Распакованный ZIP больше 512 MiB");
                workflow |= RepositoryPathPolicy.isWorkflow(path);
                if (samples.size() < 12) samples.add(path);
            }
        }
        return new Preview(files, bytes, ignored, workflow, samples, displayName(zipUri));
    }

    public ImportResult importFolder(String fullName, String requestedBranch, Uri treeUri, String message, ProgressListener listener) throws Exception {
        return new RepositoryImportController(context,api.bindCurrentSession()).importFolderBound(fullName, requestedBranch, treeUri, message, listener);
    }
    private ImportResult importFolderBound(String fullName, String requestedBranch, Uri treeUri, String message, ProgressListener listener) throws Exception {
        List<DocEntry> entries = collectFolder(treeUri);
        if (entries.isEmpty()) throw new IllegalArgumentException("В выбранной папке нет файлов для загрузки");
        BaseState base = prepareBase(fullName, requestedBranch);
        JSONArray tree = new JSONArray(); long total = 0; int done = 0; Set<String> paths = new HashSet<>();
        for (DocEntry entry : entries) {
            if (entry.size > MAX_BLOB_BYTES) throw new IllegalArgumentException("Файл больше 99 MiB: " + entry.path);
            JSONObject blob;
            try (InputStream input = require(resolver.openInputStream(entry.uri))) {
                blob = api.postBase64Blob("https://api.github.com/repos/" + fullName + "/git/blobs", input, MAX_BLOB_BYTES);
            }
            addBlobTreeEntry(tree, entry.path, blob.optString("sha", ""));
            if (entry.size > 0) total += entry.size;
            done++; if (listener != null) listener.onProgress(done, entries.size(), entry.path);
        }
        return finishCommit(fullName, base, tree, message, entries.size(), total);
    }

    public ImportResult importFile(String fullName, String requestedBranch, Uri uri, String targetPath, String message, ProgressListener listener) throws Exception {
        return new RepositoryImportController(context,api.bindCurrentSession()).importFileBound(fullName, requestedBranch, uri, targetPath, message, listener);
    }
    private ImportResult importFileBound(String fullName, String requestedBranch, Uri uri, String targetPath, String message, ProgressListener listener) throws Exception {
        String path = RepositoryPathPolicy.normalize(targetPath);
        if (path.isEmpty()) path = RepositoryPathPolicy.normalize(displayName(uri));
        if (path.isEmpty()) throw new IllegalArgumentException("Не удалось определить имя файла");
        long size = querySize(uri);
        if (size > MAX_BLOB_BYTES) throw new IllegalArgumentException("Файл больше 99 MiB. Для таких файлов используйте Git LFS или Release asset.");
        BaseState base = prepareBase(fullName, requestedBranch);
        JSONObject blob;
        try (InputStream input = require(resolver.openInputStream(uri))) {
            blob = api.postBase64Blob("https://api.github.com/repos/" + fullName + "/git/blobs", input, MAX_BLOB_BYTES);
        }
        JSONArray tree = new JSONArray(); addBlobTreeEntry(tree, path, blob.optString("sha", ""));
        if (listener != null) listener.onProgress(1, 1, path);
        return finishCommit(fullName, base, tree, message, 1, Math.max(0, size));
    }

    public ImportResult importZip(String fullName, String requestedBranch, Uri zipUri, String message, ProgressListener listener) throws Exception {
        return new RepositoryImportController(context,api.bindCurrentSession()).importZipBound(fullName, requestedBranch, zipUri, message, listener);
    }
    private ImportResult importZipBound(String fullName, String requestedBranch, Uri zipUri, String message, ProgressListener listener) throws Exception {
        Preview preview = previewZip(zipUri);
        if (preview.files == 0) throw new IllegalArgumentException("В ZIP нет файлов для загрузки");
        BaseState base = prepareBase(fullName, requestedBranch);
        JSONArray tree = new JSONArray(); long total = 0; int done = 0; Set<String> paths = new HashSet<>();
        try (InputStream raw = resolver.openInputStream(zipUri); ZipInputStream zip = new ZipInputStream(new BufferedInputStream(require(raw)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String path;
                try { path = RepositoryPathPolicy.normalize(entry.getName()); }
                catch (IllegalArgumentException unsafe) { continue; }
                if (RepositoryPathPolicy.shouldIgnore(path)) continue;
                validateArchivePath(path, paths);
                long declared = entry.getSize();
                if (declared > MAX_BLOB_BYTES) throw new IllegalArgumentException("Файл больше 99 MiB: " + path);
                CountingInputStream counting = new CountingInputStream(zip, MAX_BLOB_BYTES, MAX_TOTAL_UNCOMPRESSED_BYTES-total);
                JSONObject blob = api.postBase64Blob("https://api.github.com/repos/" + fullName + "/git/blobs", counting, MAX_BLOB_BYTES);
                addBlobTreeEntry(tree, path, blob.optString("sha", ""));
                total += counting.count;
                if (total > MAX_TOTAL_UNCOMPRESSED_BYTES) throw new IllegalArgumentException("Распакованный ZIP больше 512 MiB");
                done++; if (listener != null) listener.onProgress(done, preview.files, path);
                zip.closeEntry();
            }
        }
        return finishCommit(fullName, base, tree, message, done, total);
    }

    private ImportResult finishCommit(String fullName, BaseState base, JSONArray tree, String message, int files, long total) throws Exception {
        if (base.seeded) tree.put(new JSONObject().put("path", BOOTSTRAP_PATH).put("mode", "100644").put("type", "blob").put("sha", JSONObject.NULL));
        JSONObject treeBody = new JSONObject().put("base_tree", base.baseTreeSha).put("tree", tree);
        JSONObject treeResult = api.postJson("https://api.github.com/repos/" + fullName + "/git/trees", treeBody, true);
        String treeSha = treeResult.optString("sha", "");
        if (treeSha.isEmpty()) throw new IOException("GitHub не вернул SHA дерева");
        JSONArray parents = new JSONArray().put(base.parentSha);
        JSONObject commitBody = new JSONObject().put("message", safeCommitMessage(message)).put("tree", treeSha).put("parents", parents);
        JSONObject commit = api.postJson("https://api.github.com/repos/" + fullName + "/git/commits", commitBody, true);
        String commitSha = commit.optString("sha", "");
        if (commitSha.isEmpty()) throw new IOException("GitHub не вернул SHA коммита");
        api.patchJson("https://api.github.com/repos/" + fullName + "/git/refs/heads/" + encodePath(base.branch), new JSONObject().put("sha", commitSha).put("force", false));
        api.invalidatePrefix("https://api.github.com/repos/" + fullName);
        return new ImportResult(commitSha, base.branch, files, total);
    }

    private BaseState prepareBase(String fullName, String requestedBranch) throws Exception {
        JSONObject repo = api.getJson("https://api.github.com/repos/" + fullName);
        String branch = requestedBranch == null || requestedBranch.trim().isEmpty() ? repo.optString("default_branch", "main") : requestedBranch.trim();
        boolean seeded = false;
        JSONObject ref;
        try {
            ref = api.getJson("https://api.github.com/repos/" + fullName + "/git/ref/heads/" + encodePath(branch));
        } catch (GitHubApiClient.ApiException error) {
            if (error.statusCode != 404 && error.statusCode != 409) throw error;
            JSONObject body = new JSONObject()
                    .put("message", "Initialize repository for GitHubble import")
                    .put("content", "R2l0SHViYmxlIGltcG9ydCBib290c3RyYXAK");
            if (!branch.isEmpty()) body.put("branch", branch);
            try {
                api.putJson("https://api.github.com/repos/" + fullName + "/contents/" + BOOTSTRAP_PATH, body);
            } catch (GitHubApiClient.ApiException branchError) {
                if (branchError.statusCode != 422) throw branchError;
                body.remove("branch");
                api.putJson("https://api.github.com/repos/" + fullName + "/contents/" + BOOTSTRAP_PATH, body);
                JSONObject refreshed = api.getJson("https://api.github.com/repos/" + fullName);
                branch = refreshed.optString("default_branch", branch.isEmpty() ? "main" : branch);
            }
            seeded = true;
            ref = api.getJson("https://api.github.com/repos/" + fullName + "/git/ref/heads/" + encodePath(branch));
        }
        JSONObject object = ref.optJSONObject("object");
        String parentSha = object == null ? "" : object.optString("sha", "");
        if (parentSha.isEmpty()) throw new IOException("GitHub не вернул SHA ветки " + branch);
        JSONObject commit = api.getJson("https://api.github.com/repos/" + fullName + "/git/commits/" + parentSha);
        JSONObject tree = commit.optJSONObject("tree");
        String treeSha = tree == null ? "" : tree.optString("sha", "");
        if (treeSha.isEmpty()) throw new IOException("GitHub не вернул базовое дерево");
        return new BaseState(branch, parentSha, treeSha, seeded);
    }

    private List<DocEntry> collectFolder(Uri treeUri) throws Exception {
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        List<DocEntry> out = new ArrayList<>();
        walkChildren(treeUri, rootId, "", out);
        if (out.size() > MAX_FILES) throw new IllegalArgumentException("В папке больше " + MAX_FILES + " файлов. Разделите импорт на несколько коммитов.");
        return out;
    }

    private void walkChildren(Uri treeUri, String parentId, String prefix, List<DocEntry> out) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        String[] columns = new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE};
        try (Cursor cursor = resolver.query(children, columns, null, null, null)) {
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                long size = cursor.isNull(3) ? -1 : cursor.getLong(3);
                String path = RepositoryPathPolicy.normalize(prefix.isEmpty() ? name : prefix + "/" + name);
                if (RepositoryPathPolicy.shouldIgnore(path)) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    walkChildren(treeUri, id, path, out);
                } else {
                    if (size > MAX_BLOB_BYTES) throw new IllegalArgumentException("Файл больше 99 MiB: " + path);
                    Uri document = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                    out.add(new DocEntry(document, path, size));
                    if (out.size() > MAX_FILES) throw new IllegalArgumentException("В папке больше " + MAX_FILES + " файлов");
                }
            }
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) { }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last;
    }

    private long querySize(Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
        } catch (Exception ignored) { }
        return -1;
    }

    private static void addBlobTreeEntry(JSONArray tree, String path, String sha) throws Exception {
        if (sha == null || sha.isEmpty()) throw new IOException("GitHub не вернул SHA blob для " + path);
        tree.put(new JSONObject().put("path", path).put("mode", "100644").put("type", "blob").put("sha", sha));
    }

    private static String safeCommitMessage(String message) {
        String value = message == null ? "" : message.trim();
        return value.isEmpty() ? "Import files with GitHubble" : value;
    }

    private static String encodePath(String value) throws Exception {
        return java.net.URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20").replace("%2F", "/");
    }

    private static InputStream require(InputStream input) throws IOException {
        if (input == null) throw new IOException("Android не предоставил доступ к выбранному файлу");
        return input;
    }

    private static void validateArchivePath(String path, Set<String> paths) {
        if (path.split("/").length > MAX_PATH_DEPTH) throw new IllegalArgumentException("Слишком глубокий путь в ZIP: " + path);
        String key = path.toLowerCase(Locale.ROOT);
        if (!paths.add(key)) throw new IllegalArgumentException("Повторяющийся путь в ZIP: " + path);
    }

    private static long drainEntry(InputStream input, String path) throws IOException {
        byte[] buffer = new byte[64 * 1024]; long count = 0L; int n;
        while ((n=input.read(buffer))!=-1) {
            count += n;
            if (count > MAX_BLOB_BYTES) throw new IOException("Файл больше 99 MiB: " + path);
        }
        return count;
    }

    private static final class CountingInputStream extends InputStream {
        private final InputStream delegate; private final long perFileLimit; private final long remainingTotal; long count;
        CountingInputStream(InputStream delegate,long perFileLimit,long remainingTotal){this.delegate=delegate;this.perFileLimit=perFileLimit;this.remainingTotal=remainingTotal;}
        @Override public int read() throws IOException {int value=delegate.read();if(value!=-1)increment(1);return value;}
        @Override public int read(byte[] b,int off,int len)throws IOException{int n=delegate.read(b,off,len);if(n>0)increment(n);return n;}
        private void increment(int n)throws IOException{count+=n;if(count>perFileLimit)throw new IOException("Файл в ZIP больше 99 MiB");if(count>remainingTotal)throw new IOException("Распакованный ZIP больше 512 MiB");}
        @Override public void close(){ }
    }

}
