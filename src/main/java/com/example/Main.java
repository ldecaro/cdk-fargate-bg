package com.example;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;

import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.ext.RuntimeDelegate;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;
import org.glassfish.jersey.server.TracingConfig;

/**
 * Microservice implemented using a lightweight HTTP server bundled in JDK.
 *
 * @author Luiz Decaro
 */
@SuppressWarnings("restriction")
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
        // create a new server listening at port 8080
        final HttpServer server = HttpServer.create(new InetSocketAddress(getBaseURI().getPort()), 0);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                server.stop(0);
            }
        }));

        // create a handler wrapping the JAX-RS application
        HttpHandler handler = RuntimeDelegate.getInstance().createEndpoint(new JaxRsApplication(), HttpHandler.class);

        // map JAX-RS handler to the server root
        server.createContext(getBaseURI().getPath(), handler);

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
