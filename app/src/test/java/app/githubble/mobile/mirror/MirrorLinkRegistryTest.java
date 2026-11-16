package app.githubble.mobile.mirror;

import static org.junit.Assert.*;
import org.junit.Test;

public final class MirrorLinkRegistryTest {
    @Test public void pairIdDoesNotDependOnDirectionOrCase(){
        String a=MirrorLinkRegistry.pairId("mallstorm/OmniChat","marwell4/OmniChat");
        String b=MirrorLinkRegistry.pairId("MARWELL4/omnichat","MALLSTORM/OMNICHAT");
        assertEquals(a,b);
        assertTrue(a.matches("pair_[0-9a-f]{32}"));
        assertNotEquals(a,MirrorLinkRegistry.pairId("mallstorm/OmniChat","ntl-nam/OmniChat"));
    }
    @Test public void pairIsSymmetricAndPartnerDependsOnCurrentRepo(){
        MirrorLinkRegistry.Link link=new MirrorLinkRegistry.Link();
        link.left="mallstorm/OmniChat";link.right="marwell4/OmniChat";
        link.original=link.left;
        assertTrue(link.contains("MARWELL4/OMNICHAT"));
        assertEquals("marwell4/OmniChat",link.partner("mallstorm/OmniChat"));
        assertEquals("mallstorm/OmniChat",link.partner("marwell4/OmniChat"));
        try{link.partner("ntl-nam/OmniChat");fail();}
        catch(IllegalArgumentException expected){}
    }
    @Test public void cannotLinkRepositoryToItself(){
        try{MirrorLinkRegistry.pairId("mallstorm/OmniChat","MALLSTORM/omnichat");fail();}
        catch(IllegalArgumentException expected){}
    }
    @Test public void jsonRoundtripContainsNoAccountCredentials(){
        MirrorLinkRegistry.Link link=new MirrorLinkRegistry.Link();
        link.left="mallstorm/QwenCoderNative";link.right="marwell4/QwenCoderNative";
        link.original=link.left;
        link.id=MirrorLinkRegistry.pairId(link.left,link.right);
        link.copyJobId="abc123";
        MirrorLinkRegistry.Link read=MirrorLinkRegistry.Link.fromJson(link.json());
        assertEquals(link.id,read.id);
        assertEquals(link.left,read.partner(link.right));
        assertEquals(link.copyJobId,read.copyJobId);
        assertFalse(link.json().toString().contains("token"));
    }
}
