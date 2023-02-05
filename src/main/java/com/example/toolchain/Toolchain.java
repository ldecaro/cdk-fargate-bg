package com.example.toolchain;

import com.example.Constants;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentConfig;
import software.constructs.Construct;

public class Toolchain extends Stack {

    public static final String CODECOMMIT_REPO            = Constants.APP_NAME;
    public static final String CODECOMMIT_BRANCH          = "master";

    public Toolchain(final Construct scope, final String id, final StackProps props) throws Exception {

        super(scope, id, props);        

        Pipeline pipeline = new Pipeline(
            this,
            "BlueGreenPipeline", 
            Toolchain.CODECOMMIT_REPO,
            Toolchain.CODECOMMIT_BRANCH);

        pipeline.addStage(
            "UAT",
            EcsDeploymentConfig.ALL_AT_ONCE,
            "279211433385",
            "us-east-2");
    }
}