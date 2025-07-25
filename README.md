# A2A Java SDK for Jakarta Servers

This is the integration of the [A2A Java SDK](https://github.com/a2aproject/a2a-java) for use in Jakarta servers. It is currently tested on **WildFly**, but it should be usable in other compliant Jakarta servers such as Tomcat, Jetty, and OpenLiberty. For Quarkus, use the reference implementation in the [A2A Java SDK](https://github.com/a2aproject/a2a-java) project.

For more information about the A2A protocol, see [here](https://github.com/a2aproject/a2a-spec).

## Getting Started

To use the A2A Java SDK in your application, you will need to package it as a `.war` file. This can be done with your standard build tool.

### Packaging your application

The key to enabling A2A in your Java application is to correctly package it. Here are the general steps you need to follow:

1.  **Create a standard `.war` archive:** Your project should be configured to produce a `.war` file. This is a standard format for web applications in Java and is supported by all major runtimes.

2.  **Provide implementations for `AgentExecutor` and `AgentCard`:** The A2A SDK requires you to provide your own implementations of the `AgentExecutor` and `AgentCard` interfaces. These are the core components that define the behavior of your agent. You can find more information about them in the [A2A Java SDK documentation](https://github.com/a2aproject/a2a-java).

3.  **Manage Dependencies:** Your `.war` file must contain all necessary libraries in the `/WEB-INF/lib` directory. However, some libraries may already be provided by the application server itself.

    * **Bundling Dependencies:** For libraries not provided by the server, you must bundle them inside your `.war`.

    * **Provided Dependencies:** To avoid conflicts and reduce the size of your archive, you should exclude libraries that your target runtime already provides. For example, **WildFly** includes the **Jackson** libraries, so you do not need to package them in your application. Check the documentation for your specific runtime (Tomcat, Jetty, OpenLiberty, etc.) to see which dependencies are provided.

### Example

The [tck/pom.xml](./tck/pom.xml) is a good example of how to package an A2A application. In this case, the application is deployed in WildFly, so the dependencies included in the `.war` are tailored to what WildFly provides.

In this case we have the following dependencies:

* `org.wildfly.extras.a2a:a2a-java-sdk-server-jakarta` - this is the main dependency which transitively pulls in all the dependencies from the A2A Java SDK project. 
    * Since some of these dependencies are provided by WildFly already, we exclude those so they do not become part of the `.war`, in order to avoid inconsistencies.
* `jakarta.ws.rs:jakarta.ws.rs-api` - this is not part of the dependencies brought in via `org.wildfly.extras.a2a:a2a-java-sdk-server-jakarta` but is needed to compile the TCK module. Since it is provided by WildFly, we make the scope `provided` so it is not included in the `.war`.   
* `io.github.a2asdk:a2a-tck-server` - this is the application, which contains the `AgentExecutor` and `AgentCard` implementations for the TCK. In your case, they will most likely be implemented in the project you use to create the `.war`. 
    * In this case we exclude all transitive dependencies, since we are doing the main dependency management via the `org.wildfly.extras.a2a:a2a-java-sdk-server-jakarta` dependency.

## Running the TCK

The project includes a TCK (Technology Compatibility Kit) that you can use to test the integration with WildFly. 

To run the TCK, build the full project
```bash
mvn clean install -DskipTests
```

You now have a server provisioned with the `.war` deployed in the `tck/target/wildfly` folder.

We can start the server using the following command:

```bash
./tck/target/wildfly/bin/standalone.sh
```
Once the server is up and running, run the TCK with the instructions in [a2aproject/a2a-tck](a2aproject/a2a-tck). Make sure you check out the correct tag of `a2aproject/a2a-tck` for the protocol version we are targeting.

