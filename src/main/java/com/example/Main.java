package com.example;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

/**
 * The application includes a Git repository and a Toolchain. The Toolchain 
 * deploys the Example microservice into multiple environments using
 * AWS CodePipeline, AWS CodeBuild and AWS CodeDeploy. It supports
 * the single-account and cross-account deployment models.
 * 
 * See prerequisites (README.md) before running the application.
 */
public class Main {

    public static void main(String args[]) throws Exception {

        App  app = new App();

        String appName =    Config.APP_NAME;
        
        Environment envToolchain =   Config.toolchainEnv();

        new Toolchain(app, 
            appName+"Toolchain", 
            ToolchainStackProps.builder()
                .appName(appName)
                .env(envToolchain)
                .gitRepo(Config.CODECOMMIT_REPO)
                .build());

        new BootstrapCodeDeploy(app, 
            StackProps.builder()
                .env(Util.makeEnv())
                .stackName("AWSCodeDeployBootstrap")
                .description("This stack includes resources that are used by AWS CodeDeploy")
                .build());

        app.synth();
    }
}