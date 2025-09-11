package org.wildfly.extras.a2a.server.jakarta.tck;

import io.a2a.server.TransportMetadata;
import io.a2a.spec.TransportProtocol;

public class TempHttpJsonTransportMetadata implements TransportMetadata {
    @Override
    public String getTransportProtocol() {
        return TransportProtocol.HTTP_JSON.asString();
    }

}
