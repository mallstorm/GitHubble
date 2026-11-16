package app.githubble.mobile.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Git blob identity, including its length/type header, rather than a plain content digest. */
public final class GitObjectId {
    private GitObjectId() {}
    public static String blob(String text) {return text==null?"missing":blob(text.getBytes(StandardCharsets.UTF_8));}
    public static String blob(byte[] bytes) {
        try{MessageDigest digest=MessageDigest.getInstance("SHA-1");
            digest.update(("blob "+bytes.length+"\0").getBytes(StandardCharsets.UTF_8));digest.update(bytes);
            StringBuilder out=new StringBuilder();for(byte b:digest.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public static void requireCommit(String sha) {if(sha==null||!sha.matches("[a-f0-9]{40}"))throw new IllegalArgumentException("Нужен commit SHA");}
}
