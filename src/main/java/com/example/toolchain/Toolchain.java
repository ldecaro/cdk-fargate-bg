package com.example.toolchain;

import com.example.Constants;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentConfig;
import software.constructs.Construct;

public class Toolchain extends Stack {

    public static final String CODECOMMIT_REPO            = Constants.APP_NAME;
    public static final String CODECOMMIT_BRANCH          = "master";

    public static final String COMPONENT_ACCOUNT          = "222222222222";
    public static final String COMPONENT_REGION           = "us-east-2";    

    public Toolchain(final Construct scope, final String id, final StackProps props) throws Exception {

        super(scope, id, props);        

        Pipeline pipeline = new Pipeline(
            this,
            "BlueGreenPipeline", 
            Toolchain.CODECOMMIT_REPO,
            Toolchain.CODECOMMIT_BRANCH);

        pipeline.addStage(
            "UAT",
            EcsDeploymentConfig.CANARY_10_PERCENT_5_MINUTES,
            Toolchain.COMPONENT_ACCOUNT,
            Toolchain.COMPONENT_REGION);

        pipeline.buildPipeline();
    }
}