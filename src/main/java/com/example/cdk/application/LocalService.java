package com.example.cdk.application;

import com.example.cdk.Toolchain.StageConfig;
import com.example.cdk.Toolchain.StageConfig.EnvType;

import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ecr.assets.DockerImageAsset;
import software.constructs.Construct;

public class LocalService extends Stack implements Service {

    private String deploymentConfig =   null;
    private Environment env =   null;
    private EnvType envType =   null;

    public LocalService(Construct scope,String appName, String deploymentConfig, EnvType envType, StackProps props) {
        super(scope, appName+"-svc-"+envType.toString().toLowerCase(), props);

        this.deploymentConfig   =   deploymentConfig;
        this.env    =   props.getEnv();
        this.envType    =   envType;

        //after stacks get created during boot time, DockerImageAsset will run during synth, from inside the pipeline
        DockerImageAsset.Builder
        .create(this, appName+"-container")
        .directory("./target")
        .build();
    }

    public StageConfig getDeploymentConfig(){
        return new StageConfig(this.deploymentConfig, env, envType);
    }
}
