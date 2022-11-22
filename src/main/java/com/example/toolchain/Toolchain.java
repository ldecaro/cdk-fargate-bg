package com.example.toolchain;

import com.example.App;
import com.example.Constants;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.constructs.Construct;

public class Toolchain extends Stack {

    public static final String CODECOMMIT_REPO            = Constants.APP_NAME;
    public static final String CODECOMMIT_BRANCH          = "master";

    public static final String COMPONENT_ACCOUNT        =   App.TOOLCHAIN_ACCOUNT;
    public static final String COMPONENT_REGION         =   App.TOOLCHAIN_REGION;

    public Toolchain(final Construct scope, final String id, final StackProps props) throws Exception {

        super(scope, id, props);           

        Pipeline pipeline = new Pipeline(
            this,
            "BlueGreenPipeline", 
            Toolchain.CODECOMMIT_REPO,
            Toolchain.CODECOMMIT_BRANCH);

        pipeline.addStage(
            "UAT",
            "CodeDeployDefault.ECSLinear10PercentEvery3Minutes",
            Toolchain.COMPONENT_ACCOUNT,
            Toolchain.COMPONENT_REGION);
    }
}