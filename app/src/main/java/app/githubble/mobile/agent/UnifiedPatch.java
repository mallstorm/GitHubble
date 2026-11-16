package app.githubble.mobile.agent;

import java.util.*;
import java.util.regex.*;

/** A single-file unified patch. Requires exact positions, context and declared old/new lengths. */
public final class UnifiedPatch {
    private UnifiedPatch() {}
    private static final Pattern HEADER=Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");
    public static String apply(String path,String before,String patch)throws Exception {
        ChangeStore.path(path);if(patch==null||patch.length()>1024*1024)throw new IllegalArgumentException("Некорректный patch");
        String source=before==null?"":before;List<String> original=TextEdits.lines(source),out=new ArrayList<>();
        String[] rows=patch.split("\n",-1);int cursor=0,index=0,hunks=0;boolean finalNewline=source.endsWith("\n");
        if(rows.length>0&&rows[0].startsWith("--- ")){
            checkPath(rows[0].substring(4),path,"a/");index++;
            if(index>=rows.length||!rows[index].startsWith("+++ "))throw new IllegalArgumentException("Нет заголовка +++");
            checkPath(rows[index++].substring(4),path,"b/");
        }
        while(index<rows.length){
            if(index==rows.length-1&&rows[index].isEmpty())break;
            Matcher match=HEADER.matcher(rows[index++]);if(!match.matches())throw new IllegalArgumentException("Ожидался заголовок @@");
            int oldStart=Integer.parseInt(match.group(1)),oldCount=match.group(2)==null?1:Integer.parseInt(match.group(2));
            int newStart=Integer.parseInt(match.group(3)),newCount=match.group(4)==null?1:Integer.parseInt(match.group(4));
            int start=oldCount==0?oldStart:oldStart-1;
            if(start<cursor||start>original.size())throw new ChangeStore.Conflict("Позиция patch не совпадает");
            out.addAll(original.subList(cursor,start));cursor=start;
            if((newCount==0?newStart:newStart-1)!=out.size())throw new ChangeStore.Conflict("Новая позиция patch не совпадает");
            int removed=0,added=0;char last=0;boolean newNoNewline=false;
            while(index<rows.length&&!rows[index].startsWith("@@ ")){
                String row=rows[index];if(index==rows.length-1&&row.isEmpty()){index++;break;}
                if(row.equals("\\ No newline at end of file")){
                    if(last==0)throw new IllegalArgumentException("Маркер без строки");if(last!='-')newNoNewline=true;index++;continue;
                }
                if(row.isEmpty()||" +-".indexOf(row.charAt(0))<0)throw new IllegalArgumentException("Некорректная строка patch");
                char kind=row.charAt(0);String text=row.substring(1);last=kind;index++;
                if(kind!='+'){
                    if(cursor>=original.size()||!original.get(cursor).equals(text))throw new ChangeStore.Conflict("Контекст patch не совпадает");cursor++;removed++;
                }
                if(kind!='-'){out.add(text);added++;}
            }
            if(removed!=oldCount||added!=newCount)throw new IllegalArgumentException("Длина блока patch не совпадает");
            if(cursor==original.size())finalNewline=!newNoNewline;
            hunks++;
        }
        if(hunks==0)throw new IllegalArgumentException("Нет блоков patch");out.addAll(original.subList(cursor,original.size()));
        return String.join("\n",out)+(finalNewline&&!out.isEmpty()?"\n":"");
    }
    private static void checkPath(String value,String path,String prefix){
        String name=value.split("\t",2)[0];if(name.equals("/dev/null"))return;
        if(name.startsWith(prefix))name=name.substring(2);
        if(!name.equals(path))throw new IllegalArgumentException("Patch меняет другой файл");
    }
}
