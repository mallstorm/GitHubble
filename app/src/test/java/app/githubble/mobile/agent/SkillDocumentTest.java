package app.githubble.mobile.agent;

import static org.junit.Assert.*;

import java.util.Arrays;
import org.junit.Test;

public class SkillDocumentTest {
    private static final String OK="---\nname: repo-cleanup\ndescription: Навести порядок\ntools: github_request, actions_cleanup\n---\n1. Прочитай.\n2. Спроси.\n";

    @Test public void parsesFrontmatterAndBody(){
        SkillDocument s=SkillDocument.parse(OK,false);
        assertEquals("repo-cleanup",s.name);assertEquals("Навести порядок",s.description);
        assertEquals(Arrays.asList("github_request","actions_cleanup"),s.tools);
        assertTrue(s.body.startsWith("1. Прочитай"));assertFalse(s.builtin);
    }
    @Test public void roundTripsThroughMarkdown(){
        SkillDocument a=SkillDocument.parse(OK,false),b=SkillDocument.parse(a.toMarkdown(),false);
        assertEquals(a.name,b.name);assertEquals(a.tools,b.tools);assertEquals(a.body,b.body);
    }
    @Test public void toolsAreOptional(){assertTrue(SkillDocument.parse("---\nname: plain\ndescription: d\n---\ntext",true).tools.isEmpty());}
    @Test public void rejectsBadInput(){
        for(String bad:new String[]{"no header","---\nname: x\ndescription: d\n---\nbody","---\nname: Bad_Name\ndescription: d\n---\nb",
            "---\nname: ok-name\ndescription:\n---\nb","---\nname: ok-name\ndescription: d\n---\n","---\nname: ok-name\ndescription: d\ntools: rm -rf\n---\nb",
            "---\nname: ok-name\ndescription: d\n---\n"+new String(new char[SkillDocument.MAX_BODY+1]).replace('\0','x')}){
            try{SkillDocument.parse(bad,false);fail("accepted: "+bad.substring(0,Math.min(30,bad.length())));}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void catalogHasOnlyNameAndOneLine(){
        String c=SkillDocument.formatCatalog(Arrays.asList(SkillDocument.parse(OK,false)));
        assertTrue(c.contains("- repo-cleanup — Навести порядок"));assertFalse(c.contains("Прочитай"));
        assertEquals("",SkillDocument.formatCatalog(java.util.Collections.<SkillDocument>emptyList()));
    }
    @Test public void builtinAssetsAllParse()throws Exception{
        java.io.File dir=new java.io.File("src/main/assets/skills");
        if(!dir.isDirectory())dir=new java.io.File("app/src/main/assets/skills");
        java.io.File[] files=dir.listFiles((d,n)->n.endsWith(".md"));
        assertNotNull(files);assertTrue(files.length>=9);
        for(java.io.File f:files){
            SkillDocument s=SkillDocument.parse(new String(java.nio.file.Files.readAllBytes(f.toPath()),"UTF-8"),true);
            assertEquals(f.getName(),s.name+".md");
        }
    }
}
