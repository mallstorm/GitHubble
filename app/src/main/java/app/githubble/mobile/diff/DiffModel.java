package app.githubble.mobile.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parsed unified patch used by unified/split renderers and line-comment navigation. */
public final class DiffModel {
    public enum Kind { HEADER, HUNK, CONTEXT, ADD, DELETE, META }
    public static final class Line {
        public final Kind kind; public final int oldLine; public final int newLine; public final String text;
        Line(Kind kind,int oldLine,int newLine,String text){this.kind=kind;this.oldLine=oldLine;this.newLine=newLine;this.text=text;}
        public String side(){return kind==Kind.DELETE?"LEFT":"RIGHT";}
        public int commentLine(){return kind==Kind.DELETE?oldLine:newLine;}
    }
    private static final Pattern HUNK=Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");
    public final List<Line> lines;
    private DiffModel(List<Line> lines){this.lines=Collections.unmodifiableList(lines);}
    public static DiffModel parse(String patch){
        List<Line> out=new ArrayList<>();int oldNo=0,newNo=0;
        String[] raw=(patch==null?"":patch).replace("\r","").split("\n",-1);
        for(String value:raw){Matcher m=HUNK.matcher(value);if(m.matches()){oldNo=Integer.parseInt(m.group(1));newNo=Integer.parseInt(m.group(3));out.add(new Line(Kind.HUNK,0,0,value));continue;}
            if(value.startsWith("+++ ")||value.startsWith("--- ")){out.add(new Line(Kind.HEADER,0,0,value));continue;}
            if(value.startsWith("+")&&!value.startsWith("+++")){out.add(new Line(Kind.ADD,0,newNo++,value.substring(1)));continue;}
            if(value.startsWith("-")&&!value.startsWith("---")){out.add(new Line(Kind.DELETE,oldNo++,0,value.substring(1)));continue;}
            if(value.startsWith(" ")){out.add(new Line(Kind.CONTEXT,oldNo++,newNo++,value.substring(1)));continue;}
            out.add(new Line(Kind.META,0,0,value));
        }
        return new DiffModel(out);
    }
    public int changedLines(){int n=0;for(Line l:lines)if(l.kind==Kind.ADD||l.kind==Kind.DELETE)n++;return n;}
    public List<Line> displayLines(boolean collapseContext,int contextLimit){
        if(!collapseContext)return lines;List<Line> out=new ArrayList<>();int runStart=-1;
        for(int i=0;i<=lines.size();i++){boolean context=i<lines.size()&&lines.get(i).kind==Kind.CONTEXT;if(context&&runStart<0)runStart=i;if((!context||i==lines.size())&&runStart>=0){int count=i-runStart;if(count<=contextLimit*2+1){out.addAll(lines.subList(runStart,i));}else{out.addAll(lines.subList(runStart,runStart+contextLimit));out.add(new Line(Kind.META,0,0,"… "+(count-contextLimit*2)+" строк контекста скрыто …"));out.addAll(lines.subList(i-contextLimit,i));}runStart=-1;}if(i<lines.size()&&!context)out.add(lines.get(i));}
        return out;
    }
}
