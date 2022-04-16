package com.example;

import com.example.api.infrastructure.Service;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.pipelines.CodePipeline;
import software.amazon.awscdk.services.codecommit.IRepository;
import software.amazon.awscdk.services.iam.IRole;
import software.constructs.Construct;

public class Toolchain extends Stack {

    private ToolchainHelper helper  =   new ToolchainHelper(this);
    
    public Toolchain(Construct scope, String id, final ToolchainStackProps props) throws Exception{

        super(scope, id, props);

        String appName  =   props.getAppName();
        IRepository gitRepo =   props.getGitRepo();  

        Environment envAlpha = Util.makeEnv((String)scope.getNode().tryGetContext("alpha"));
        Environment envBeta = Util.makeEnv((String)scope.getNode().tryGetContext("beta"));

        Service alpha = helper.createService(
            DeploymentConfig.EnvType.ALPHA, 
            envAlpha,   
            DeploymentConfig.DEPLOY_ALL_AT_ONCE, 
            props);

        Service beta = helper.createService(
            DeploymentConfig.EnvType.BETA, 
            envBeta, 
            DeploymentConfig.DEPLOY_LINEAR_10_PERCENT_EVERY_1_MINUTES, 
            props);         

        //we need to create a reference pipeline to add the role otherwise it throws Error: Pipeline not created yet
        IRole pipelineRole   =   helper.createPipelineRole(appName);  

        CodePipeline pipeline   =   helper.createPipeline(
            appName, 
            gitRepo, 
            helper.createTemplatePipeline(pipelineRole), 
            new DeploymentConfig[]{alpha.getDeploymentConfig(), beta.getDeploymentConfig()});  

        helper.configureDeployStage(alpha, pipeline, pipelineRole, helper.getCodePipelineSource(), props);
        helper.configureDeployStage(beta, pipeline, pipelineRole, helper.getCodePipelineSource(), props);

        CfnOutput.Builder.create(this, "PipelineRole")
            .description("Pipeline Role name")
            .value(pipelineRole.getRoleName())
            .build();                    

        try{
            CfnOutput.Builder.create(this, "PipelineName")
                .description("Pipeline name")
                .value(pipeline.getPipeline()==null ? "n/a" : pipeline.getPipeline().getPipelineName())
                .build();
        }catch(Exception e){
            System.out.println("Not showing output PipelineName because it has not yet been created");
        }
    }
}
