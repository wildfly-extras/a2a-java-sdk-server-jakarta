package org.wildfly.extras.a2a.server.apps.jakarta;


import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import io.a2a.server.apps.common.AbstractA2AServerTest;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.container.annotation.ArquillianTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.wildfly.extras.a2a.server.jakarta.test.common.A2ATestResource;
import org.wildfly.extras.a2a.server.jakarta.test.common.RestApplication;


@ArquillianTest
@RunAsClient
public class WildFlyJarJakartaA2AServerTest extends AbstractA2AServerTest {

    public WildFlyJarJakartaA2AServerTest() {
        super(8080);
    }

    @Deployment
    public static WebArchive createTestArchive() throws Exception {

        // Get wildfly-jar path
        JavaArchive wildFlyJar = ShrinkWrap.createFromZipFile(JavaArchive.class,
                new File(A2AServerResource.class.getProtectionDomain().getCodeSource().getLocation().toURI()));

        WebArchive archive = ShrinkWrap.create(WebArchive.class, "ROOT.war")
                .addAsLibrary(wildFlyJar)
                // Extra dependencies needed by the tests
                .addPackage(AbstractA2AServerTest.class.getPackage())
                .addPackage(A2ATestResource.class.getPackage())
                // Add deployment descriptors
                .addAsManifestResource("META-INF/beans.xml", "beans.xml")
                .addAsWebInfResource("META-INF/beans.xml", "beans.xml")
                .addAsWebInfResource("WEB-INF/web.xml", "web.xml");
        archive.toString(true);
        return archive;
    }
}
