package com.example.iac;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import software.amazon.awscdk.Environment;

public class Util {
	
	public Util() {}
	
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
//        		bytes =	Files.readAllBytes( Paths.get( Thread.currentThread().getContextClassLoader().getResource("com/amazon/aws/architecture/"+filename).toURI() )	);
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
    
    // Helper method to build an environment
    public static Environment makeEnv(String account, String region) {
        account = (account == null) ? System.getenv("CDK_DEFAULT_ACCOUNT") : account;
        region = (region == null) ? System.getenv("CDK_DEFAULT_REGION") : region;
		//System.out.println("Using Account-Region: "+ account+"-"+region);
        return Environment.builder()
                .account(account)
                .region(region)
                .build();
    }
    
    
    public void updateFile(String filenameSrc, String filenameDest, String what, String newText) {
    	
    	FileWriter fw		=	null;
    	BufferedWriter bw	=	null;
    	BufferedReader br	=	null;
    	FileReader	fr		=	null;
        File file = new File( filenameSrc );
        File file2= new File( filenameDest );
        try{
            String verify, putData;
            file2.createNewFile();
            fw = new FileWriter(file2);
            bw = new BufferedWriter(fw);
            
            fr = new FileReader(file);
            br = new BufferedReader(fr);
            while( (verify=br.readLine()) != null ){
                       
                if(verify != null){ 
                    putData = verify.replace(what, newText);
                    bw.write(putData);
                    bw.write(System.lineSeparator());
                }                
            }
            bw.flush();
        }catch(IOException e){
        	e.printStackTrace();
        }finally {
        	try {
        		if(bw != null) bw.close();
        		if(br != null) br.close();
        	}catch(Exception e) {
        		System.out.println(e.getMessage());
        	}
        }
    }

    public void updateFile(String filenameSrc, String filenameDest, Map<String,String> tokenReplacement) {
    	
    	FileWriter fw		=	null;
    	BufferedWriter bw	=	null;
    	BufferedReader br	=	null;
    	FileReader	fr		=	null;
        File file = new File( filenameSrc );
        File file2= new File( filenameDest );
        try{
            String verify;
            file2.createNewFile();
            fw = new FileWriter(file2);
            bw = new BufferedWriter(fw);
            
            fr = new FileReader(file);
            br = new BufferedReader(fr);
            while( (verify=br.readLine()) != null ){
                       
                if(verify != null){ 
                    for(Map.Entry<String,String> entry:tokenReplacement.entrySet()){
                        verify = verify.replace(entry.getKey(), entry.getValue());
                    }
                    bw.write(verify);
                    bw.write(System.lineSeparator());
                }                
            }
            bw.flush();
        }catch(IOException e){
        	e.printStackTrace();
        }finally {
        	try {
        		if(bw != null) bw.close();
        		if(br != null) br.close();
        	}catch(Exception e) {
        		System.out.println(e.getMessage());
        	}
        }
    }    

	public static String randomString(int length){

		char[] buf = new char[length];
		String alphanum = ("ABCDEFGHIJKLMNOPQRSTUVWXYZ".toLowerCase())+"0123456789";
		char[] symbols = alphanum.toCharArray();

        for (int idx = 0; idx < buf.length; ++idx)
            buf[idx] = symbols[(int)(Math.random()*symbols.length)];
		return new String(buf);
	}

	public static void updateAppCDKJson() throws Exception{

		String content = new String(Files.readAllBytes(Paths.get("cdk.json")));
		String token = "mvn -e -q compile exec:java";
		if( ! content.contains(token)){
			System.out.println("Inside the cdk.json file an element named app must have the following value: `"+token+"`");
			System.exit(0);
		}
		content	=	content.replaceAll(token, "mvn -e -q -Paws-build compile exec:java");
		System.out.println( content );
		File f = new File("cdk.json");
		f.delete();
		Files.write( Paths.get("cdk.json"), content.getBytes());
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

    public void updateConfigurationFiles(String appName, String account, String region, String ecsTaskExecutionRole){

        Util util   =   new Util();
        //buildspec.yml
        util.updateFile("buildspec-template.yml", "buildspec.yml", new HashMap<String,String>(){{
            put("ACCT_NUMBER", account);
            put("APPLICATION", appName);
            put("REGION", region);
        }});
        //appspec.yml
        util.updateFile("appspec-template.yaml", "appspec.yaml", "APPLICATION", appName);
        //taskdef
        util.updateFile("taskdef-template.json", "taskdef.json", new HashMap<String,String>(){{
            put("APPLICATION", appName);
            put("TASK_EXEC_ROLE", "arn:aws:iam::"+account+":role/"+ecsTaskExecutionRole);
        }});        
             
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
