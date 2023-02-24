package com.example.toolchain;

import com.example.Constants;

import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentConfig;
import software.constructs.Construct;

public class Toolchain extends Stack {

    public static final String CODECOMMIT_REPO            = Constants.APP_NAME;
    public static final String CODECOMMIT_BRANCH          = "master";

    public static final String COMPONENT_ACCOUNT          = "742584497250";
    public static final String COMPONENT_REGION           = "us-east-1";    

    public Toolchain(final Construct scope, final String id, final StackProps props) throws Exception {

        super(scope, id, props);       
        
        Pipeline.Builder.create(this, "BlueGreenPipeline")
            .setGitRepo(Toolchain.CODECOMMIT_REPO)
            .setGitBranch(Toolchain.CODECOMMIT_BRANCH)
            .addStage("UAT", 
                EcsDeploymentConfig.CANARY_10_PERCENT_5_MINUTES, 
                Environment.builder()
                    .account(Toolchain.COMPONENT_ACCOUNT)
                    .region(Toolchain.COMPONENT_REGION)
                    .build())
            .build();
    }
}