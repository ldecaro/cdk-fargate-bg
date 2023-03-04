package com.example;

import com.example.toolchain.Toolchain;

import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

/**
 * The application includes a Toolchain stack. The Toolchain 
 * creates a continuous delivery pipeline that builds and deploys 
 * the Example component into multiple environments using deployment
 * of type Blue/Green implemented using AWS CodePipeline, AWS CodeBuild 
 * and AWS CodeDeploy. 
 * 
 * The Blue/Green pipeline supports the single-account and 
 * cross-account deployment models.
 * 
 * See prerequisites (README.md) before running the application.
 */
public class App extends software.amazon.awscdk.App {

    private static final String TOOLCHAIN_ACCOUNT             = "742584497250";
    private static final String TOOLCHAIN_REGION              = "us-east-1";

    public static void main(String args[]) throws Exception {

        App app = new App();

        new Toolchain(
            app, 
            Constants.APP_NAME+"Toolchain",
            StackProps.builder()
                .env(Environment.builder()
                    .account(App.TOOLCHAIN_ACCOUNT)
                    .region(App.TOOLCHAIN_REGION)
                    .build())
                .build());

        app.synth();
    }
}