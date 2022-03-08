package com.example.cdk;

import com.example.cdk.CrossAccountApplicationStack.ServiceAssetStackProps;
import com.example.cdk.PipelineStack.DeploymentConfig;
import com.example.cdk.PipelineStack.PipelineStackProps;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

public class HelloWorldApp {

    public static void main(String args[]) throws Exception{

        App  app = new App();

        Environment envPipeline =   Util.makeEnv();
        Environment envTarget   =   Util.makeEnv("279211433385", "us-east-1");

        String appName = (String)app.getNode().tryGetContext("appName");
        if( appName == null || "".equals(appName.trim() )){
            appName = "ecs-microservice";
        }

        //if necessary, pack directory to upload
        final String buildNumber = System.getenv("CODEBUILD_BUILD_NUMBER");
        Boolean IS_CREATING  =   buildNumber == null ? Boolean.TRUE : Boolean.FALSE;
        if( IS_CREATING ){
            Util.createSrcZip(appName);
        }
              
        //deploying the stacks...                                 
        GitStack git    =   new GitStack(app, 
            appName+"-git", 
            appName,
            IS_CREATING,
            StackProps.builder()
                .env(envPipeline)
                .terminationProtection(Boolean.FALSE)
                .build());

        //synth the application stacks, it generates the .assets file, we use it to retrieve the ECR repository information to configure CodeDeploy
        ApplicationStack alphaService = new ApplicationStack(
            app, 
            appName+"-svc-1", 
            appName,
            StackProps.builder()
                .env(envPipeline)
                .stackName(appName+"-app-alpha")
                .build());       

        CrossAccountApplicationStack betaService   =   new CrossAccountApplicationStack(
            app,
            appName+"-svc-2",
            appName,
            ServiceAssetStackProps.builder()
                .env(envTarget)
                .envPipeline(envPipeline)
                .stackName(appName+"-app-beta")
                .build()
            );

        PipelineStack pipeline   =   new PipelineStack(app, 
            appName+"-pipeline", 
            PipelineStackProps.builder()
                .appName(appName)
                .env(envPipeline)
                .envTarget(envTarget)
                .gitRepo(git.getGitRepository())
                .deploymentConfigs(new DeploymentConfig[]{ 
                    new DeploymentConfig(
                        DeploymentConfig.DEPLOY_ALL_AT_ONCE, 
                        envPipeline), 
                    new DeploymentConfig(
                        DeploymentConfig.DEPLOY_LINEAR_10_PERCENT_EVERY_1_MINUTES, 
                        envTarget, 
                        betaService.getCodeDeployActionRole(), 
                        betaService.getDeploymentGroup()), 
                })
                .build());  

        pipeline.addDependency(git);
        pipeline.addDependency(alphaService);        
        pipeline.addDependency(betaService);
        app.synth();
    }
}
