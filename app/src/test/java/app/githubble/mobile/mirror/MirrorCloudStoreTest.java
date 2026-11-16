package app.githubble.mobile.mirror;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class MirrorCloudStoreTest {
    private MirrorLinkRegistry.Link example(String left,String right,long when){
        MirrorLinkRegistry.Link link=new MirrorLinkRegistry.Link();
        link.id=MirrorLinkRegistry.pairId(left,right);
        if(left.compareToIgnoreCase(right)<0){link.left=left;link.right=right;}
        else{link.left=right;link.right=left;}
        link.original=left;
        link.createdAt=when;
        link.updatedAt=when;
        return link;
    }
    @Test public void exportImportPreservesDirectionFreePairs()throws Exception{
        MirrorLinkRegistry.Link a=example("mallstorm/OmniChat","marwell4/OmniChat",100);
        MirrorLinkRegistry.Link b=example("marwell4/QwenCoderNative","ntl-nam/QwenCoderNative",120);
        String json=MirrorCloudStore.encode(Arrays.asList(b,a));
        assertTrue(json.contains("githubble-mirror-links"));
        assertFalse(json.contains("access_token"));
        assertFalse(json.contains("private_key"));
        List<MirrorLinkRegistry.Link> read=MirrorCloudStore.decode(json);
        assertEquals(2,read.size());
        assertTrue(MirrorCloudStore.equalRegistry(Arrays.asList(a,b),read));
        assertTrue(MirrorCloudStore.equalRegistry(read,Arrays.asList(b,a)));
        assertFalse(MirrorCloudStore.equalRegistry(read,Collections.singletonList(a)));
    }
    @Test public void rejectsUnrelatedDocumentAndInvalidPairId()throws Exception{
        try{MirrorCloudStore.decode("{\"hello\":\"world\"}");fail("must reject unrelated JSON");}
        catch(IllegalArgumentException expected){}
        MirrorLinkRegistry.Link link=example("mallstorm/OmniChat","marwell4/OmniChat",100);
        String valid=MirrorCloudStore.encode(Collections.singletonList(link));
        try{MirrorCloudStore.decode(valid.replace(link.id,"pair_00000000000000000000000000000000"));fail();}
        catch(IllegalArgumentException expected){}
    }
    @Test public void emptyDocumentCanBeInitialized()throws Exception{
        assertTrue(MirrorCloudStore.decode("").isEmpty());
        assertTrue(MirrorCloudStore.decode("  ").isEmpty());
    }
    @Test public void duplicateIdsAreRejected()throws Exception{
        MirrorLinkRegistry.Link link=example("mallstorm/a","marwell4/a",100);
        String encoded=MirrorCloudStore.encode(Arrays.asList(link,link));
        try{MirrorCloudStore.decode(encoded);fail("must reject duplicate links");}
        catch(IllegalArgumentException expected){}
    }
}
