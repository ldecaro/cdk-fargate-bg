package com.example;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import com.example.iac.Util;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Root resource (exposed at "myresource" path)
 */
@Path("/")
public class HelloWorldResource {

    private Util util = new Util();
    private String html =   null;

    public HelloWorldResource(){
        if( html == null ){
            html = util.getFile("com/example/home.html");
        }
    }
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
