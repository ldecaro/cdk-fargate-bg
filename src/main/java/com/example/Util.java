package com.example;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import software.amazon.awscdk.Environment;

public class Util {
	
	public Util() {}
	
	//TODO move this to inside the microservice.
    public String getFile(String filename) {
		
		byte[] bytes = null;
        try {
        	
        	final Map<String, String> env = new HashMap<>();
        	final String[] array = this.getClass().getClassLoader().getResource(filename).toURI().toString().split("!");
        	final FileSystem fs = FileSystems.newFileSystem(URI.create(array[0]), env);
        	final Path path = fs.getPath(array[1]);
        	bytes = Files.readAllBytes( path );
        	fs.close();
        }catch(IllegalArgumentException a) {
        	try {
        		bytes = Files.readAllBytes( Paths.get(this.getClass().getClassLoader().getResource(filename).toURI()));                	
			} catch (URISyntaxException e) {
				System.out.println("App::Cannot load parameter file "+filename+". URISyntaxException:"+e.getMessage());
				e.printStackTrace();
			} catch( IOException ioe) {
				System.out.println("App::Cannot load parameter file "+filename+". IOException:"+ioe.getMessage());
				ioe.printStackTrace();
			}

		} catch (URISyntaxException e) {
			System.out.println("App::Cannot load parameter file "+filename+". URISyntaxException:"+e.getMessage());
			e.printStackTrace();
		} catch( IOException ioe) {
			System.out.println("App::Cannot load parameter file "+filename+". IOException:"+ioe.getMessage());
			ioe.printStackTrace();
		}
        String fileContent	=	new String(bytes);
        return fileContent;
	}

	public static String getPipelineAccount(){
		return System.getenv("CDK_DEPLOY_PIPELINE");
	}
    
	public static Environment makeEnv(){
		return Util.makeEnv(null,null);
	}

    // Helper method to build an environment
    public static Environment makeEnv(String account, String region) {

		String localAccount = (account == null) ? System.getenv("CDK_DEPLOY_ACCOUNT") : System.getenv("AWS_DEFAULT_ACCOUNT");
        String localRegion = (region == null) ? System.getenv("CDK_DEPLOY_REGION") : System.getenv("AWS_DEFAULT_REGION");

		account = localAccount == null ?  account : localAccount;
		region = localRegion == null ? region : localRegion;

		//System.out.println("Using Account-Region: "+ account+"-"+region);
        return Environment.builder()
                .account(account)
                .region(region)
                .build();
    }
}