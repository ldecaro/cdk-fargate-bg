package com.example;

import java.util.Arrays;
import java.util.List;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.pipelines.CodePipeline;
import software.amazon.awscdk.services.codecommit.IRepository;
import software.constructs.Construct;

public class Toolchain extends Stack {

    private ToolchainHelper helper  =   new ToolchainHelper(this);
    
    public Toolchain(Construct scope, String id, final ToolchainStackProps props) throws Exception {

        super(scope, id, props);

        String appName      =   props.getAppName();
        IRepository gitRepo =   props.getGitRepo();  

        //the number of elements in this array will determine the number of deployment stages in the pipeline.
        List<DeploymentConfig> deployConfig = Arrays.asList( new DeploymentConfig[]{
            helper.createDeploymentConfig(
                DeploymentConfig.EnvType.ALPHA, 
                DeploymentConfig.DEPLOY_ALL_AT_ONCE, 
                props), 
            helper.createDeploymentConfig(
                DeploymentConfig.EnvType.BETA,
                DeploymentConfig.DEPLOY_LINEAR_10_PERCENT_EVERY_1_MINUTES, 
                props)        
        } );
        
        CodePipeline pipeline   =   helper.createPipeline(
            appName, 
            gitRepo);  

        //adding each deployment stage based in the number of deploymentConfigs inside the array
        deployConfig.forEach(dc-> helper.configureDeployStage(
            dc, 
            pipeline, 
            helper.getCodePipelineSource(), 
            props));                 

    }
}
