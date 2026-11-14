package app.githubble.mobile.agent;

import java.util.*;

/** Bounded exact line hunks, including CRLF and final-newline changes. No fuzzy matching. */
public final class TextEdits {
    private TextEdits() {}
    static List<String> lines(String text){
        if(text.isEmpty())return new ArrayList<>();List<String> out=new ArrayList<>(Arrays.asList(text.split("\n",-1)));
        if(text.endsWith("\n"))out.remove(out.size()-1);return out;
    }
    public static final class Hunk {
        public final int start,removed,newStart;
        public final List<String> added;
        Hunk(int start,int removed,List<String> added){this(start,removed,added,start);}
        Hunk(int start,int removed,List<String> added,int newStart){this.start=start;this.removed=removed;this.added=added;this.newStart=newStart;}
        public String preview(String before){List<String> old=lines(before);StringBuilder s=new StringBuilder("@@ -"+(removed==0?start:start+1)+","+removed+" +"+(added.isEmpty()?newStart:newStart+1)+","+added.size()+" @@\n");
            for(int i=start;i<start+removed;i++)s.append('-').append(old.get(i)).append('\n');for(String line:added)s.append('+').append(line).append('\n');if(removed==0&&added.isEmpty())s.append("[Изменение завершающего перевода строки]\n");return s.toString();}
    }
    public static List<Hunk> hunks(String before,String after){
        List<String> fullA=lines(before),fullB=lines(after);int prefix=0,suffix=0;
        while(prefix<Math.min(fullA.size(),fullB.size())&&fullA.get(prefix).equals(fullB.get(prefix)))prefix++;
        while(suffix<Math.min(fullA.size(),fullB.size())-prefix&&fullA.get(fullA.size()-1-suffix).equals(fullB.get(fullB.size()-1-suffix)))suffix++;
        List<String> a=fullA.subList(prefix,fullA.size()-suffix),b=fullB.subList(prefix,fullB.size()-suffix);List<Hunk> out=new ArrayList<>();
        if((long)a.size()*b.size()>1000000){out.add(new Hunk(prefix,a.size(),new ArrayList<>(b)));
            if(before.endsWith("\n")!=after.endsWith("\n"))out.add(new Hunk(fullA.size(),0,Collections.emptyList(),fullB.size()));return out;}
        int[][] lcs=new int[a.size()+1][b.size()+1];for(int i=a.size()-1;i>=0;i--)for(int j=b.size()-1;j>=0;j--)
            lcs[i][j]=a.get(i).equals(b.get(j))?1+lcs[i+1][j+1]:Math.max(lcs[i+1][j],lcs[i][j+1]);
        int i=0,j=0;while(i<a.size()||j<b.size()){
            if(i<a.size()&&j<b.size()&&a.get(i).equals(b.get(j))){i++;j++;continue;}
            int start=i,newStart=j;List<String> inserted=new ArrayList<>();
            while(i<a.size()||j<b.size()){
                if(i<a.size()&&j<b.size()&&a.get(i).equals(b.get(j)))break;
                if(i<a.size()&&(j==b.size()||lcs[i+1][j]>=lcs[i][j+1]))i++;else inserted.add(b.get(j++));
            }
            out.add(new Hunk(prefix+start,i-start,inserted,prefix+newStart));
        }
        if(before.endsWith("\n")!=after.endsWith("\n"))out.add(new Hunk(fullA.size(),0,Collections.emptyList(),fullB.size()));
        return out;
    }
    public static String select(String before,String after,Set<Integer> selected){
        List<Hunk> hunks=hunks(before,after);if(selected==null||selected.isEmpty())throw new IllegalArgumentException("Выберите блок");
        for(int i:selected)if(i<0||i>=hunks.size())throw new IllegalArgumentException("Неизвестный блок");
        List<String> output=lines(before);boolean newline=before.endsWith("\n");
        for(int i=hunks.size()-1;i>=0;i--){if(!selected.contains(i))continue;Hunk h=hunks.get(i);
            if(h.start==lines(before).size()&&h.removed==0&&h.added.isEmpty())newline=after.endsWith("\n");
            output.subList(h.start,h.start+h.removed).clear();output.addAll(h.start,h.added);
        }
        return String.join("\n",output)+(newline&&!output.isEmpty()?"\n":"");
    }
    public static String diff(String path,String before,String after){
        StringBuilder out=new StringBuilder("--- "+(before==null?"/dev/null":"a/"+path)+"\n+++ "+(after==null?"/dev/null":"b/"+path)+"\n");
        String old=before==null?"":before,next=after==null?"":after;for(Hunk h:hunks(old,next))out.append(h.preview(old));
        if(old.endsWith("\n")!=next.endsWith("\n"))out.append("[Final newline changed]\n");return out.toString();
    }
}
