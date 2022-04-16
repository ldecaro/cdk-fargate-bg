package com.example.api.runtime;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import com.example.Util;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Root resource (exposed at "myresource" path)
 */
@Path("/")
public class HelloWorldResource {

    private static Util util = new Util();
    private static String html =   null;

    static{
        html = util.getFile("com/example/api/runtime/home.html");
    }

    public HelloWorldResource(){}
    
    /**
     * Method handling HTTP GET requests. The returned object will be sent
     * to the client as "text/plain" media type.
     *
     * @return String that will be returned as a text/plain response.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public InputStream getIt() {
        return new ByteArrayInputStream(html.getBytes());
    }
}
