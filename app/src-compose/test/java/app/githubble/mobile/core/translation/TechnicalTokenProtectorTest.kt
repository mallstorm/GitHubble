package app.githubble.mobile.core.translation
import org.junit.Assert.assertEquals
import org.junit.Test
class TechnicalTokenProtectorTest{
 @Test fun preservesCodeUrlAndPath(){val s="Install `git clone` from https://github.com/a/b then open /tmp/a.txt";val p=TechnicalTokenProtector();val x=p.protect(s);assertEquals(s,p.restore(x.text,x.tokens))}
 @Test fun preservesFence(){val s="Text\n```bash\n./gradlew assembleRelease\n```";val p=TechnicalTokenProtector();val x=p.protect(s);assertEquals(s,p.restore(x.text,x.tokens))}
}
