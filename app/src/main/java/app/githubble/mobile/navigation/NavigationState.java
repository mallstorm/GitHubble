package app.githubble.mobile.navigation;

import android.os.Bundle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;

/** Persists route names across Activity recreation. Parameterized screens are reloaded from their route string. */
public final class NavigationState {
    private static final String KEY_STACK = "navigation_stack";
    private final Deque<String> stack = new ArrayDeque<>();
    public void push(String route) { if (route != null && !route.isEmpty()) stack.push(route); }
    public String pop() { return stack.poll(); }
    public boolean isEmpty() { return stack.isEmpty(); }
    public void clear() { stack.clear(); }
    public int size() { return stack.size(); }
    public void save(Bundle out) { out.putStringArrayList(KEY_STACK, new ArrayList<>(stack)); }
    public void restore(Bundle in) { if(in==null)return; ArrayList<String> a=in.getStringArrayList(KEY_STACK); if(a!=null){stack.clear(); for(int i=a.size()-1;i>=0;i--)stack.push(a.get(i));} }
}
