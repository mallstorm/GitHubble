package app.githubble.mobile.agent;

/** Capability decisions are scoped to exact model, endpoint and protocol, never a provider-wide boolean. */
public final class TransportCapabilities {
    private final java.util.Set<String> confirmed=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private String key(String endpoint, String model, NativeToolTransport.Protocol protocol) {
        return endpoint+"\n"+model+"\n"+protocol.name();
    }
    public void confirmed(String endpoint,String model,NativeToolTransport.Protocol protocol,boolean supported) {
        String key=key(endpoint,model,protocol);
        if (supported) confirmed.add(key); else confirmed.remove(key);
    }
    public boolean supports(String endpoint,String model,NativeToolTransport.Protocol protocol) {
        return confirmed.contains(key(endpoint,model,protocol));
    }
}
