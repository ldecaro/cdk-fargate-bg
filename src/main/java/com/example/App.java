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

    public static void main(String args[]) throws Exception {

        App app = new App();

        String appName =    Toolchain.APP_NAME;
        
        Environment envToolchain =   Toolchain.toolchainEnv();

        new Toolchain(
            app, 
            appName+"Toolchain",
            appName,
            Toolchain.CODECOMMIT_REPO,
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
                .description("This stack includes resources that are used by AWS CodeDeploy as part of a BlueGreen pipeline")
                .build());

        app.synth();
    }
}