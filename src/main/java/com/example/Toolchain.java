package com.example;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.constructs.Construct;

public class Toolchain extends Stack {

    public Toolchain(final Construct scope, final String id, final String appName, final String gitRepo, final StackProps props) throws Exception {

        super(scope, id, props);

        new BlueGreenPipeline(
            this,
            "BlueGreenPipeline", 
            appName, 
            gitRepo, 
            BlueGreenConfig.getStages(
                scope, 
                appName));
    }
}