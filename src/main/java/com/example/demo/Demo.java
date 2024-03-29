/* (C)2023 */
package com.example.demo;

import com.example.demo.toolchain.infrastructure.ContinuousDeployment;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentConfig;

/**
 * The application includes a ContinuousDeployment stack. This stack
 * creates a continuous delivery pipeline that builds and deploys
 * the Service component into one or multiple environments. It uses 
 * AWS CodePipeline, AWS CodeBuild and AWS CodeDeploy to implement a
 * Blue/Green deployment. The Service component is part of a Demo
 * application that belongs to Example.com.
 * 
 * The Blue/Green pipeline supports the single-account and
 * cross-account deployment models.
 *
 * See prerequisites (README.md) before running the application.
 */
public class Demo {

    private static final String TOOLCHAIN_ACCOUNT = "742584497250";
    private static final String TOOLCHAIN_REGION = "us-east-1";
    // CodeCommit account is the same as the toolchain account
    public static final String CODECOMMIT_REPO = Constants.APP_NAME;
    public static final String CODECOMMIT_BRANCH = "main";

    public static final String COMPONENT_ACCOUNT = Demo.TOOLCHAIN_ACCOUNT;
    public static final String COMPONENT_REGION = Demo.TOOLCHAIN_REGION;

    public static void main(String args[]) throws Exception {

        App app = new App();

        // note that the ContinuousDeployment build() method encapsulates
        // implementaton details for adding role permissions in cross-account scenarios
        ContinuousDeployment.Builder.create(app, Constants.APP_NAME+"Pipeline")
                .stackProperties(StackProps.builder()
                        .env(Environment.builder()
                                .account(Demo.TOOLCHAIN_ACCOUNT)
                                .region(Demo.TOOLCHAIN_REGION)
                                .build())
                        .build())
                .setGitRepo(Demo.CODECOMMIT_REPO)
                .setGitBranch(Demo.CODECOMMIT_BRANCH)
                .addStage(
                        "UAT",
                        EcsDeploymentConfig.CANARY_10_PERCENT_5_MINUTES,
                        Environment.builder()
                                .account(Demo.COMPONENT_ACCOUNT)
                                .region(Demo.COMPONENT_REGION)
                                .build())
                .build();

        app.synth();
    }
}
