package org.wildfly.extras.a2a.server.apps.jakarta;

import io.a2a.server.TransportMetadata;
import io.a2a.spec.TransportProtocol;

public class WildflyJSONRPCTransportMetadata implements TransportMetadata {
    @Override
    public String getTransportProtocol() {
        return TransportProtocol.JSONRPC.asString();
    }
}
