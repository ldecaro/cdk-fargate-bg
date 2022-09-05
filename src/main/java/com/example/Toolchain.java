package com.example;

import software.amazon.awscdk.Stack;
import software.constructs.Construct;

public class Toolchain extends Stack {

    public Toolchain(Construct scope, String id, final ToolchainStackProps props) throws Exception {

        super(scope, id, props);

        String appName  =   props.getAppName();
        String gitRepo  =   props.getGitRepo();

        new BlueGreenPipeline(
            scope,
            "BlueGreenPipeline", 
            appName, 
            gitRepo, 
            Config.getStages(
                scope, 
                appName));

    }
}