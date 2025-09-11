package org.wildfly.extras.a2a.test.server.apps.jakarta;


import java.io.File;
import java.util.List;

import io.a2a.client.ClientBuilder;
import io.a2a.client.http.A2AHttpClient;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfigBuilder;
import io.a2a.server.PublicAgentCard;
import io.a2a.server.apps.common.AbstractA2AServerTest;
import io.a2a.spec.Event;
import io.a2a.spec.TransportProtocol;
import io.a2a.transport.jsonrpc.handler.JSONRPCHandler;
import io.a2a.util.Assert;
import mutiny.zero.ZeroPublisher;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.container.annotation.ArquillianTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.wildfly.extras.a2a.server.apps.jakarta.WildflyJSONRPCTransportMetadata;


@ArquillianTest
@RunAsClient
public class JakartaA2AServerTest extends AbstractA2AServerTest {

    @Override
    protected String getTransportProtocol() {
        return TransportProtocol.JSONRPC.asString();
    }


    @Override
    protected String getTransportUrl() {
        return "http://localhost:8080";
    }

    @Override
    protected void configureTransport(ClientBuilder builder) {
        builder.withTransport(JSONRPCTransport.class, new JSONRPCTransportConfigBuilder());
    }

    public JakartaA2AServerTest() {
        super(8080);
    }

    @Deployment
    public static WebArchive createTestArchive() throws Exception {
        final JavaArchive[] libraries = List.of(
                // a2a-java-sdk-common.jar
                getJarForClass(Assert.class),
                // a2a-java-sdk-http-client
                getJarForClass(A2AHttpClient.class),
                // a2a-java-sdk-server-common.jar
                getJarForClass(PublicAgentCard.class),
                // a2a-java-sdk-spec.jar
                getJarForClass(Event.class),
                // a2a-java-sdk-transport-jsonrpc
                getJarForClass(JSONRPCHandler.class),
                // a2a-java-sdk-jakarta-jsonrpc.jar - contains WildflyJSONRPCTransportMetadata
                getJarForClass(WildflyJSONRPCTransportMetadata.class),
                // mutiny-zero.jar. This is provided by some WildFly layers, but not always, and not in
                // the server provisioned by Glow when inspecting our war
                getJarForClass(ZeroPublisher.class)).toArray(new JavaArchive[0]);


        WebArchive archive = ShrinkWrap.create(WebArchive.class, "ROOT.war")
                .addAsLibraries(libraries)
                // Extra dependencies needed by the tests
                .addPackage(AbstractA2AServerTest.class.getPackage())
                .addPackage(A2ATestResource.class.getPackage())
                // Add deployment descriptors
                .addAsManifestResource("META-INF/beans.xml", "beans.xml")
                .addAsWebInfResource("WEB-INF/web.xml", "web.xml");
        archive.toString(true);
        return archive;
    }

    static JavaArchive getJarForClass(Class<?> clazz) throws Exception {
        File f = new File(clazz.getProtectionDomain().getCodeSource().getLocation().toURI());
        return ShrinkWrap.createFromZipFile(JavaArchive.class, f);
    }

}
