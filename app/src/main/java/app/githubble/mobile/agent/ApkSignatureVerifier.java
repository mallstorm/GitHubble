package app.githubble.mobile.agent;

import java.io.File;
import java.security.MessageDigest;
import java.util.Locale;
import org.json.JSONObject;

/** Cryptographic v1/v2/v3 verification; a magic signing-block marker never proves validity. */
public final class ApkSignatureVerifier {
    private ApkSignatureVerifier(){}
    public static JSONObject verify(File apk)throws Exception {
        com.android.apksig.ApkVerifier.Result result=new com.android.apksig.ApkVerifier.Builder(apk).build().verify();
        boolean ok=result.isVerified()&&!result.getSignerCertificates().isEmpty();
        String digest="";if(ok){byte[] bytes=MessageDigest.getInstance("SHA-256").digest(result.getSignerCertificates().get(0).getEncoded());
            StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));digest=out.toString();}
        return new JSONObject().put("signature_verified",ok).put("certificate_sha256",digest);
    }
}
