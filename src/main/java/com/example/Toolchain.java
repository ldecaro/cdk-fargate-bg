package com.example;

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

        CodePipeline pipeline   =   helper.createPipeline(
            appName, 
            gitRepo);  

        DeploymentConfig.getStages(scope, appName)
            .forEach(dc-> helper.configureDeployStage(
                dc, 
                pipeline, 
                helper.getCodePipelineSource(), 
                props));
    }
}
