package com.example.cdk;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ecr.assets.DockerImageAsset;
import software.constructs.Construct;

public class ApplicationStack extends Stack {

    public ApplicationStack(Construct scope, String id, String appName, StackProps props) {
        super(scope, id, props);

        //after stacks get created during boot time, DockerImageAsset will run during synth, from inside the pipeline
        DockerImageAsset.Builder
        .create(this, appName+"-container")
        .directory("./target")
        .build();
    }
}
