package com.example.cdk_fargate_bg.api.runtime;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Example resource returns example.html directly at the root context.
 */
@Path("/")
public class ExampleResource {

    private static String HTML =   null;

    public ExampleResource(){
		if( ExampleResource.HTML == null )
        	ExampleResource.HTML = getFile("com/example/cdk_fargate_bg/api/runtime/example.html");;
    }
    
    /**
     * Method handling HTTP GET requests. The returned object will be sent
     * to the client as "text/plain" media type.
     *
     * @return String that will be returned as a text/plain response.
     */
    @GET
	@Produces(MediaType.TEXT_HTML)
    public String example() {
        return ExampleResource.HTML;
    }	

    private String getFile(String filename) {
		
		byte[] bytes = null;
        try {
        	final Map<String, String> env = new HashMap<>();
        	final String[] array = Thread.currentThread().getContextClassLoader().getResource(filename).toURI().toString().split("!");
        	final FileSystem fs = FileSystems.newFileSystem(Thread.currentThread().getContextClassLoader().getResource(filename).toURI(), env);
        	final java.nio.file.Path path = fs.getPath(array[1]);
        	bytes = Files.readAllBytes( path );

        }catch(IllegalArgumentException a) {
        	try {
        		bytes = Files.readAllBytes( Paths.get(this.getClass().getClassLoader().getResource(filename).toURI()));                	
			} catch (URISyntaxException e) {
				System.out.println("ExampleResource::Cannot HTML file "+filename+". URISyntaxException:"+e.getMessage());
				e.printStackTrace();
			} catch( IOException ioe) {
				System.out.println("ExampleResource::Cannot HTML file "+filename+". IOException:"+ioe.getMessage());
				ioe.printStackTrace();
			}
		} catch (URISyntaxException e) {
			System.out.println("ExampleResource::Cannot HTMl file "+filename+". URISyntaxException:"+e.getMessage());
			e.printStackTrace();
		} catch( IOException ioe) {
			System.out.println("ExampleResource::Cannot HTML file "+filename+". IOException:"+ioe.getMessage());
			ioe.printStackTrace();
		}
        String fileContent	=	new String(bytes);
        return fileContent;
	}    	
}