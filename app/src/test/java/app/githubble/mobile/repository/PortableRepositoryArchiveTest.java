package app.githubble.mobile.repository;

import static org.junit.Assert.*;
import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Security and workflow regression checks for portable full repository recovery. */
public final class PortableRepositoryArchiveTest {
    @Test public void rejectsPathTraversalAndBadNames() {
        assertFalse(PortableRepositoryArchive.safeEntry("../manifest.json"));
        assertFalse(PortableRepositoryArchive.safeEntry("lfs/objects/../../hack"));
        assertFalse(PortableRepositoryArchive.safeEntry("/tmp/data"));
        assertFalse(PortableRepositoryArchive.safeEntry("C:/windows"));
        assertFalse(PortableRepositoryArchive.safeEntry("a\\b"));
        assertTrue(PortableRepositoryArchive.safeEntry("lfs/objects/ab/cd/abcd"));
        assertTrue(PortableRepositoryArchive.safeEntry("repository.bundle"));
    }

    private static byte[] archive(boolean corrupt, boolean missing)throws Exception {
        byte[] bundle="BUNDLE".getBytes(StandardCharsets.UTF_8);
        byte[] meta="{}".getBytes(StandardCharsets.UTF_8);
        byte[] refs="refs/heads/main 0123456789012345678901234567890123456789\n".getBytes(StandardCharsets.UTF_8);
        JSONObject files=new JSONObject();
        files.put("repository.bundle",new JSONObject().put("sha256",
                PortableRepositoryArchive.hash(bundle)).put("bytes",bundle.length));
        files.put("metadata.json",new JSONObject().put("sha256",
                PortableRepositoryArchive.hash(meta)).put("bytes",meta.length));
        files.put("git-refs.txt",new JSONObject().put("sha256",
                PortableRepositoryArchive.hash(refs)).put("bytes",refs.length));
        JSONObject manifest=new JSONObject().put("format",PortableRepositoryArchive.FORMAT)
                .put("source","mallstorm/Telegram-Premium")
                .put("default_branch","main")
                .put("default_sha","0123456789012345678901234567890123456789")
                .put("complete",true).put("release_binaries",false)
                .put("files",files);
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(buffer)){
            zip.putNextEntry(new ZipEntry("repository.bundle"));
            zip.write(corrupt?"WRONG!".getBytes(StandardCharsets.UTF_8):bundle);zip.closeEntry();
            zip.putNextEntry(new ZipEntry("metadata.json"));
            zip.write(meta);zip.closeEntry();
            if(!missing){
                zip.putNextEntry(new ZipEntry("git-refs.txt"));
                zip.write(refs);zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        }
        return buffer.toByteArray();
    }

    @Test public void verifiedFullArchiveReportsActualManifest()throws Exception {
        PortableRepositoryArchive.Inspection v=PortableRepositoryArchive.inspect(
                new ByteArrayInputStream(archive(false,false)),null);
        assertEquals("mallstorm/Telegram-Premium",v.source);
        assertEquals("main",v.branch);
        assertEquals(4,v.files);
        assertEquals(1,v.refs);
    }

    @Test public void tamperedBundleIsRejected()throws Exception {
        try {
            PortableRepositoryArchive.inspect(new ByteArrayInputStream(archive(true,false)),null);
            fail("Tampering accepted");
        }catch(SecurityException correct){}
    }

    @Test public void incompleteArchiveIsRejected()throws Exception {
        try {
            PortableRepositoryArchive.inspect(new ByteArrayInputStream(archive(false,true)),null);
            fail("Missing refs accepted");
        }catch(SecurityException correct){}
    }

    @Test public void generatedBackupAndRestoreUseRealGitBundle() {
        String backup=FullRepositoryBackupWorkflow.yaml();
        assertTrue(backup.contains("git bundle create"));
        assertTrue(backup.contains("git lfs fetch --all origin"));
        assertTrue(backup.contains("git-refs.txt"));
        assertTrue(backup.contains("upload-artifact@v4"));
        assertTrue(backup.contains("workflow_dispatch:"));
        String restore=FullRepositoryRestoreWorkflow.yaml();
        assertTrue(restore.contains("git clone --mirror"));
        assertTrue(restore.contains("git push --atomic --force-with-lease"));
        assertTrue(restore.contains("Архив применён"));
        assertTrue(restore.contains("GITHUBBLE_FULL_RESTORE_"));
    }

    @Test public void sourceAccountMustOwnRepository() {
        FullRepositoryBackupController.validateIdentity("mallstorm/Telegram-Premium","mallstorm");
        try {
            FullRepositoryBackupController.validateIdentity("mallstorm/Telegram-Premium","Digger401");
            fail("Cross-account backup accepted");
        }catch(SecurityException correct){}
    }

    @Test public void progressNeverClaimsVerifiedBeforeArtifactsExist()throws Exception {
        JSONArray steps=new JSONArray();
        for(int i=0;i<10;i++)steps.put(new JSONObject().put("conclusion","success"));
        assertTrue(FullRepositoryBackupController.stagePercent(steps)<100);
    }
}
