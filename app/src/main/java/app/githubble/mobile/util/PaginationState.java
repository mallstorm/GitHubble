package app.githubble.mobile.util;

/** Small testable helper shared by REST and GraphQL paginated screens. */
public final class PaginationState {
    private int page = 1;
    private String cursor = "";
    private boolean loading;
    private boolean hasMore = true;
    public int page(){return page;} public String cursor(){return cursor;} public boolean loading(){return loading;} public boolean hasMore(){return hasMore;}
    public boolean begin(){if(loading||!hasMore)return false;loading=true;return true;}
    public void completeRest(int received,int pageSize){loading=false;hasMore=received>=pageSize;if(hasMore)page++;}
    public void completeGraphQl(String endCursor,boolean next){loading=false;cursor=endCursor==null?"":endCursor;hasMore=next;}
    public void fail(){loading=false;}
    public void reset(){page=1;cursor="";loading=false;hasMore=true;}
}
