package app.githubble.mobile.agent;

import java.util.Arrays;

/** Line diff with bounded CPU. A coarse replacement is explicitly labelled approximate. */
public final class FileChangeStats {
    public final int added,removed;
    public final boolean approximate;
    private FileChangeStats(int a,int r,boolean approx){added=a;removed=r;approximate=approx;}
    private static String[] lines(String s){
        if(s==null||s.isEmpty())return new String[0];
        String[] all=s.replace("\r\n","\n").split("\n",-1);
        return all[all.length-1].isEmpty()?Arrays.copyOf(all,all.length-1):all;
    }
    public static FileChangeStats between(String before,String after){
        if(java.util.Objects.equals(before,after))return new FileChangeStats(0,0,false);
        String[] a=lines(before),b=lines(after);
        int start=0,endA=a.length,endB=b.length;
        while(start<endA&&start<endB&&a[start].equals(b[start]))start++;
        while(endA>start&&endB>start&&a[endA-1].equals(b[endB-1])){endA--;endB--;}
        int n=endA-start,m=endB-start;
        if(n==0||m==0)return new FileChangeStats(m,n,false);
        // LCS uses linear memory; bound pathological generated files on mobile CPUs.
        if((long)n*m>4000000)return new FileChangeStats(m,n,true);
        int[] row=new int[m+1];
        for(int i=start;i<endA;i++){
            int diagonal=0;
            for(int j=1;j<=m;j++){
                int old=row[j];
                row[j]=a[i].equals(b[start+j-1])?diagonal+1:Math.max(row[j],row[j-1]);
                diagonal=old;
            }
        }
        return new FileChangeStats(m-row[m],n-row[m],false);
    }
}
