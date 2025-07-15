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
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;


@ArquillianTest
@RunAsClient
public class JakartaA2AServerTest extends AbstractA2AServerTest {

    public JakartaA2AServerTest() {
        super(8080);
    }

    @Deployment
    public static WebArchive createTestArchive() throws IOException {
        final List<String> prefixes = List.of(
                    "a2a-java-sdk-client",
                    "a2a-java-sdk-common",
                    "a2a-java-sdk-server-common",
                    "a2a-java-sdk-spec",
                    "mutiny"
            );
        List<File> libraries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get("target").resolve("lib"))) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                if (prefixes.stream().anyMatch(fileName::startsWith)) {
                    libraries.add(file.toFile());
                }
            }
        }
        WebArchive archive = ShrinkWrap.create(WebArchive.class, "ROOT.war")
                .addAsLibraries(libraries.toArray(new File[libraries.size()]))
                .addPackage(AbstractA2AServerTest.class.getPackage())
                .addClass(A2ARequestFilter.class)
                .addClass(A2AServerResource.class)
                .addClass(A2ATestResource.class)
                .addClass(RestApplication.class)
                .addAsManifestResource("META-INF/beans.xml", "beans.xml")
                .addAsWebInfResource("META-INF/beans.xml", "beans.xml")
                .addAsWebInfResource("WEB-INF/web.xml", "web.xml");
        archive.toString(true);
        return archive;
    }
}
