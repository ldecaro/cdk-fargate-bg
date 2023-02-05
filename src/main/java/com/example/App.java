package com.example;

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

    public static void main(String args[]) throws Exception {

        App app = new App();
        
        Environment envToolchain =   App.toolchainEnv();

        new Toolchain(
            app, 
            Constants.APP_NAME+"Toolchain",
            StackProps.builder()
                .env(envToolchain)
                .build());

        app.synth();
    }

    public static Environment toolchainEnv(){
	
		return Environment.builder().account("742584497250").region("us-east-1").build();
	}      
}