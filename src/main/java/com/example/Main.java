package com.example;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;
import org.glassfish.jersey.server.TracingConfig;

import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.RuntimeDelegate;

/**
 * Microservice implemented using a lightweight HTTP server bundled in JDK.
 *
 * @author Luiz Decaro
 */
public class Main extends ResourceConfig{
	

	public Main () {

        // Tracing support.
        property(ServerProperties.TRACING, TracingConfig.ON_DEMAND.name());
	}
		
    /**
     * Starts the lightweight HTTP server serving the JAX-RS application.
     *
     * @return new instance of the lightweight HTTP server
     * @throws IOException
     */
    static HttpServer startServer() throws IOException {    

        // create a handler wrapping the JAX-RS application
        HttpHandler handler = RuntimeDelegate.getInstance().createEndpoint(new JaxRsApplication(), HttpHandler.class);
        
        // create a new server listening at port 8080
        final HttpServer server = GrizzlyHttpServerFactory.createHttpServer(getBaseURI(), true);
        server.getServerConfiguration().addHttpHandler(handler);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                server.shutdownNow();
            }
        }));

        // start the server
        server.start();

        return server;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
    	
    	
        System.out.println("\"Hello-World\" Service");

        startServer();

        System.out.println("Application started.\n"
                + "Try accessing " + getBaseURI() + " in the browser.\n"
                + "Hit ^C to stop the application...");

        Thread.currentThread().join();
    }

    private static int getPort(int defaultPort) {
        final String port = System.getProperty("jersey.config.test.container.port");
        if (null != port) {
            try {
                return Integer.parseInt(port);
            } catch (NumberFormatException e) {
                System.out.println("Value of jersey.config.test.container.port property"
                        + " is not a valid positive integer [" + port + "]."
                        + " Reverting to default [" + defaultPort + "].");
            }
        }
        return defaultPort;
    }

    /**
     * Gets base {@link URI}.
     *
     * @return base {@link URI}.
     */
    public static URI getBaseURI() throws UnknownHostException {
        return UriBuilder.fromUri("http://"+InetAddress.getLocalHost().toString().substring(0,InetAddress.getLocalHost().toString().indexOf("/")+1)).port(getPort(8080)).build();
    }
}
