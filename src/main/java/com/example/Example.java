package com.example;

import com.example.toolchain.ContinuousDeployment;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentConfig;

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
public class Example {

    private static final String TOOLCHAIN_ACCOUNT         = "742584497250";
    private static final String TOOLCHAIN_REGION          = "us-east-1";
    //CodeCommit account is the same as the toolchain account
    public static final String CODECOMMIT_REPO            = Constants.APP_NAME;
    public static final String CODECOMMIT_BRANCH          = "master";

    public static final String COMPONENT_ACCOUNT          = "742584497250";
    public static final String COMPONENT_REGION           = "us-east-1";      

    public static void main(String args[]) throws Exception {

        App app = new App();

        //note that the ContinuousDeployment build() method encapsulates 
        //implementaton details for adding role permissions in cross-account scenarios
        ContinuousDeployment.Builder.create(app, Constants.APP_NAME)
            .stackProperties(StackProps.builder()
                .env(Environment.builder()
                    .account(Example.TOOLCHAIN_ACCOUNT)
                    .region(Example.TOOLCHAIN_REGION)
                    .build())
                .stackName(Constants.APP_NAME)
                .build())
            .setGitRepo(Example.CODECOMMIT_REPO)
            .setGitBranch(Example.CODECOMMIT_BRANCH)
            .addStage("UAT", 
                EcsDeploymentConfig.CANARY_10_PERCENT_5_MINUTES, 
                Environment.builder()
                    .account(Example.COMPONENT_ACCOUNT)
                    .region(Example.COMPONENT_REGION)
                    .build())
            .build();

        app.synth();
    }
}