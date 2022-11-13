package com.example;

import com.example.bootstrap.CodeDeployBootstrap;
import com.example.toolchain.Toolchain;

import software.amazon.awscdk.Aws;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

/**
 * The application includes a Toolchain and an AWS CodeDeploy 
 * bootstrap stacks. The Toolchain creates a BlueGreen pipeline
 * that builds and deploys the Example component into multiple 
 * environments using AWS CodePipeline, AWS CodeBuild and 
 * AWS CodeDeploy. 
 * 
 * The BlueGreen pipeline supports the single-account and 
 * cross-account deployment models.
 * 
 * See prerequisites (README.md) before running the application.
 */
public class App extends software.amazon.awscdk.App {

    public static final String TOOLCHAIN_ACCOUNT             = "123456789012";
    public static final String TOOLCHAIN_REGION              = "us-east-1";

    public static void main(String args[]) throws Exception {

        App app = new App();
        
        Environment envToolchain =   App.toolchainEnv();

        new Toolchain(
            app, 
            Constants.APP_NAME+"Toolchain",
            StackProps.builder()
                .env(envToolchain)
                .build());    
                
        new CodeDeployBootstrap(
            app, 
            "CodeDeployBootstrap",
            StackProps.builder()
                .env(Environment.builder()
                    .account(Aws.ACCOUNT_ID)
                    .region(Aws.REGION)
                    .build())
                .description("This stack includes Blue/Green pipeline resources that are used by AWS CodeDeploy")
                .build());

        app.synth();
    }

    public static Environment toolchainEnv(){
	
		return Environment.builder().account(TOOLCHAIN_ACCOUNT).region(TOOLCHAIN_REGION).build();
	}      
}