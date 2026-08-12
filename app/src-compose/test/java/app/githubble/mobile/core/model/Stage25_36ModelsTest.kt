package app.githubble.mobile.core.model
import org.junit.Assert.*
import org.junit.Test
class Stage25_36ModelsTest{ @Test fun releaseKeepsDownloadMetadata(){val a=ReleaseAsset(1,"app.apk",1024,"https://example.invalid/app.apk");val r=ReleaseItem(2,"v1","Release",null,false,null,"",listOf(a));assertEquals("app.apk",r.assets.single().name)};@Test fun pullDraftFlagIsPreserved(){assertTrue(PullRequestItem(1,"WIP","open","dev",true,null,"").draft)} }
