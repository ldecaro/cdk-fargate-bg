package com.example.cdk;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ecr.assets.DockerImageAsset;
import software.constructs.Construct;

public class ServiceAssetStack extends Stack {
    
    public ServiceAssetStack(Construct scope, String id, String appName, final StackProps props) {

        super(scope, id, props);
        
        DockerImageAsset.Builder
            .create(this, appName+"-container")
            .directory("./target")
            .build();   
           
    }
}
