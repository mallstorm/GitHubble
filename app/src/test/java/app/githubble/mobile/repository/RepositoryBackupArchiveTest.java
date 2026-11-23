package app.githubble.mobile.repository;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class RepositoryBackupArchiveTest {
    private static JSONObject sample() throws Exception {
        JSONObject all=new JSONObject();
        for(String section:RepositoryBackupArchive.SECTIONS) {
            all.put(section,"repository".equals(section)
                    ?new JSONObject().put("description","Archive test")
                    :new JSONObject().put("items",new JSONArray()));
        }
        all.getJSONObject("labels").getJSONArray("items")
                .put(new JSONObject().put("name","feature").put("color","00cc33"));
        return new JSONObject()
                .put("repository","mallstorm/GitHubble")
                .put("default_branch","main")
                .put("default_sha","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .put("private",true)
                .put("created_at","2026-11-23T06:00:00Z")
                .put("sections",all);
    }
    private static byte[] encode(JSONObject snapshot) throws Exception {
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        RepositoryBackupArchive.write(snapshot,output);
        return output.toByteArray();
    }

    @Test public void archiveRoundTripPreservesAllSectionsAndMetadata() throws Exception {
        byte[] zip=encode(sample());
        RepositoryBackupArchive.Archive decoded=RepositoryBackupArchive.read(
                new ByteArrayInputStream(zip));
        assertEquals("githubble-repository-metadata-v1",decoded.manifest.getString("format"));
        assertEquals("metadata_only",decoded.manifest.getString("scope"));
        assertTrue(decoded.manifest.getBoolean("private"));
        assertEquals("mallstorm/GitHubble",decoded.manifest.getString("repository"));
        assertEquals("feature",decoded.list("labels").getJSONObject(0).getString("name"));
        assertEquals(RepositoryBackupArchive.SECTIONS.length,decoded.manifest.getJSONObject(
                "entries").length());
    }

    @Test public void alteredSectionFailsSha256Verification() throws Exception {
        byte[] original=encode(sample());
        Map<String,byte[]> entries=new LinkedHashMap<>();
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(original))) {
            ZipEntry e;
            while((e=zip.getNextEntry())!=null) {
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                byte[] buffer=new byte[4096];
                int count;while((count=zip.read(buffer))>=0)out.write(buffer,0,count);
                entries.put(e.getName(),out.toByteArray());
            }
        }
        byte[] changed="{\"items\":[{\"name\":\"not-feature\"}]}".getBytes(StandardCharsets.UTF_8);
        entries.put("labels.json",changed);
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(output)) {
            for(Map.Entry<String,byte[]> e:entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());zip.closeEntry();
            }
        }
        try {
            RepositoryBackupArchive.read(new ByteArrayInputStream(output.toByteArray()));
            fail("Tampered archive accepted");
        }catch(SecurityException expected) { }
    }

    @Test public void missingSectionIsRejected() throws Exception {
        JSONObject snapshot=sample();
        snapshot.getJSONObject("sections").remove("issues");
        try {
            encode(snapshot);
            fail("Missing issues section accepted");
        }catch(IllegalArgumentException expected) { }
    }

    @Test public void unexpectedEntryIsRejectedAsUnsafe() throws Exception {
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("../credentials.txt"));
            zip.write("test".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        try {
            RepositoryBackupArchive.read(new ByteArrayInputStream(output.toByteArray()));
            fail("Unsafe ZIP entry accepted");
        }catch(SecurityException expected) { }
    }

    @Test public void rejectsInvalidSourceShaAndUnknownSections() throws Exception {
        JSONObject snapshot=sample().put("default_sha","not-a-sha");
        try {encode(snapshot);fail("Invalid SHA accepted");}
        catch(SecurityException expected) { }
        try {RepositoryBackupArchive.file("../../secret");fail("Unsafe path accepted");}
        catch(IllegalArgumentException expected) { }
    }
}
