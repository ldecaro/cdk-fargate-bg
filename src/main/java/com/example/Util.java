package com.example;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.example.DeploymentConfig.EnvType;

import software.amazon.awscdk.Environment;

public class Util {
	
	public Util() {}

	public static Properties props	=	null;
	
	static{
        InputStream is = null;
        try {
            props = new Properties();
            is = Util.class.getResourceAsStream("/app.properties");
            props.load(is);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
	
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

	public static String getTrustedAccount(){
		return System.getenv("CDK_DEPLOY_TRUST");
	}
    
	public static Environment makeEnv(){
		return Util.makeEnv(null,null);
	}

    // Helper method to build an environment
    public static Environment makeEnv(String account, String region) {

		account = (account == null) ? System.getenv("CDK_DEPLOY_ACCOUNT") : account;
        region = (region == null) ? System.getenv("CDK_DEPLOY_REGION") : region;
        account = (account == null) ? System.getenv("CDK_DEFAULT_ACCOUNT") : account;
        region = (region == null) ? System.getenv("CDK_DEFAULT_REGION") : region;
		//System.out.println("Using Account-Region: "+ account+"-"+region);
        return Environment.builder()
                .account(account)
                .region(region)
                .build();
    }

	private static Environment makeEnv(String env){

        if( env != null && !"undefined".equals(env) && !"".equals(env.trim())){
            return Util.makeEnv(env.split("/")[0], env.split("/")[1]);
        }else{
            return Util.makeEnv();
        }		
	}

	public static Environment getEnv(EnvType type){

		if(Util.props == null){
			loadProperties();
		}

		switch(type){

			case ALPHA:{
				return Util.makeEnv(props.getProperty(EnvType.ALPHA.toString().toLowerCase()));
			}
			case BETA:{
				return Util.makeEnv(props.getProperty(EnvType.BETA.toString().toLowerCase()));
			}
			case GAMMA:{
				return Util.makeEnv(props.getProperty(EnvType.GAMMA.toString().toLowerCase()));
			}
			default:{
				System.out.println("Util.getEnv is trying to access an environment that doesn't exist");
				throw new IllegalArgumentException("Trying to access an environment that doesn't exist: "+type);
			}
		}
	}

	public static Environment toolchainEnv(){

		if(Util.props == null){
			loadProperties();
		}
		return Util.makeEnv(Util.props.getProperty("toolchain"));
	}

	public static String appName(){

		if(Util.props == null ){
			loadProperties();
		}
		return Util.props.getProperty("appName");
	}

	public static Boolean addProperty(String property, String value){

		Util.props.put(property, value);
		try{
			Util.props.store(new FileOutputStream("app.properties"), null);
			return true;
		}catch(IOException e){
			System.out.println("Could not save property "+property+" to file app.properties. Msg: "+property);
			return false;
		}
	}
     
	private static void loadProperties() {

		Util.props = new Properties();
		try{
			Util.props.load(Thread.currentThread().getContextClassLoader().getResourceAsStream("app.properties"));
		}catch(IOException ioe){
			System.out.println("Could not read the app.properties file. Msg: "+ioe.getMessage());
			System.out.println("Abandoning...");
			System.exit(0);
		}catch(IllegalArgumentException ie){
			System.out.println("Could not parse the app.properties file. Msg: "+ie.getMessage());
			System.out.println("Abandoning...");
			System.exit(0);
		}
	}

	public static void zipDirectory(ZipOutputStream zos, File fileToZip, String parentDirectoryName, final Boolean REMOVE_ROOT) throws Exception {

		if (fileToZip == null || !fileToZip.exists()) {
			return;
		}

		String zipEntryName = fileToZip.getName();
		if (parentDirectoryName!=null && !parentDirectoryName.isEmpty()) {
			zipEntryName = parentDirectoryName + "/" + fileToZip.getName();
		}
	
		if (fileToZip.isDirectory()) {
			// System.out.println("+" + zipEntryName);
			if( zipEntryName.endsWith("target") || 
				zipEntryName.endsWith("cdk.out") || 
				zipEntryName.endsWith(".git") || 
				zipEntryName.endsWith(".aws-sam") ||
				zipEntryName.endsWith(".settings") ||
				zipEntryName.endsWith(".vscode") ||
				// zipEntryName.endsWith("lambda") ||
				zipEntryName.endsWith("dist") ){
				// System.out.println("Skipping "+zipEntryName);
			}else{
				for (File file : fileToZip.listFiles()) {
					if( REMOVE_ROOT ){
						zipDirectory(zos, file, null, Boolean.FALSE);
					}else{
						zipDirectory(zos, file, zipEntryName, Boolean.FALSE);
					}
				}
			}
		} else {
			// System.out.println("   " + zipEntryName);
			byte[] buffer = new byte[1024];
			FileInputStream fis = new FileInputStream(fileToZip);
			zos.putNextEntry(new ZipEntry(zipEntryName));
			int length;
			while ((length = fis.read(buffer)) > 0) {
				zos.write(buffer, 0, length);
			}
			zos.closeEntry();
			fis.close();				
		}
	}

    public static void createSrcZip(final String appName) throws Exception {

        File outputFile = new File("dist");
        if(outputFile.exists()){
            String[]entries = outputFile.list();
            for(String s: entries){
                File currentFile = new File(outputFile.getPath(),s);
                currentFile.delete();
            }
            outputFile.delete();
        }
        outputFile.mkdirs();
        FileOutputStream fos = new FileOutputStream(outputFile.getName()+"/"+appName+"-src.zip", false);
        ZipOutputStream zos = new ZipOutputStream(fos);
        Util.zipDirectory(zos, new File(System.getProperty("user.dir")), null, Boolean.TRUE);
        zos.flush();
        fos.flush();
        zos.close();
        fos.close();
    }	
}
