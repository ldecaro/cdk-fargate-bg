package com.example.cdk;

import com.example.cdk.Pipeline.StageConfig;
import com.example.cdk.Pipeline.PipelineStackProps;
import com.example.cdk.Pipeline.StageConfig.EnvType;
import com.example.cdk.application.Application;
import com.example.cdk.application.CrossAccountApplication;
import com.example.cdk.application.CrossAccountApplication.CrossAccountApplicationProps;
import com.example.cdk.application.IApplication;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;

/**
 * This reference application deploys a greeting microservice on an ECS Fargate cluster using
 * AWS CodePipeline, AWS CodeBuild and AWS CodeDeploy. It is possible to create ECS environments
 * using one or more accounts depending on the use of Application or CrossAccountApplication.
 */
public class ReferenceApp {

    public static void main(String args[]) throws Exception{

        App  app = new App();

        Environment envPipeline =   Util.makeEnv();
        Environment envAlpha    =   Util.makeEnv();
        Environment envBeta     =   Util.makeEnv();

        String alphaEnvironment = (String)app.getNode().tryGetContext("alpha"); // 123456789012/us-east-1
        if( alphaEnvironment != null && !"undefined".equals(alphaEnvironment) && !"".equals(alphaEnvironment.trim())){
            envAlpha = Util.makeEnv(alphaEnvironment.split("/")[0], alphaEnvironment.split("/")[1]);
        }
        
        String betaEnvironment = (String)app.getNode().tryGetContext("beta"); // 987654321098/us-east-1
        if( betaEnvironment != null && !"undefined".equals(betaEnvironment) && !"".equals(betaEnvironment.trim())){
            envBeta = Util.makeEnv(betaEnvironment.split("/")[0], betaEnvironment.split("/")[1]);
        }

        String appName = (String)app.getNode().tryGetContext("appName");
        if( appName == null || "".equals(appName.trim()) || "undefined".equals(appName)){
            appName = "ecs-microservice";
        }

        System.out.println("Pipeline env: "+envPipeline.getAccount()+"/"+envPipeline.getRegion());
        System.out.println("Alpha env: "+envAlpha.getAccount()+"/"+envAlpha.getRegion());
        System.out.println("Beta env: "+envBeta.getAccount()+"/"+envBeta.getRegion());

        //if necessary, pack directory to upload
        final String buildNumber = System.getenv("CODEBUILD_BUILD_NUMBER");
        Boolean IS_CREATING  =   buildNumber == null ? Boolean.TRUE : Boolean.FALSE;
        if( IS_CREATING ){
            Util.createSrcZip(appName);
        }
              
        //deploying the stacks...                                 
        Git git    =   new Git(app, 
            appName+"-git", 
            appName,
            IS_CREATING,
            StackProps.builder()
                .env(envPipeline)
                .terminationProtection(Boolean.FALSE)
                .build());

        //synth the application stacks, it generates the .assets file, we use it to retrieve the ECR repository information to configure CodeDeploy
        IApplication alphaService;
        IApplication betaService;

        if( envPipeline.equals(envAlpha) ){
            System.out.println("Env ALPHA is in the same account");
            alphaService = new Application(
                app, 
                appName,
                StageConfig.DEPLOY_ALL_AT_ONCE,
                EnvType.ALPHA,
                StackProps.builder()
                    .env(envAlpha)
                    .stackName(appName+"-app-alpha")
                    .build());       
        }else{
            System.out.println("Env ALPHA is in a cross account");
            alphaService   =   new CrossAccountApplication(
                app,
                appName,
                StageConfig.DEPLOY_ALL_AT_ONCE,
                EnvType.ALPHA, //defines the deployment order
                CrossAccountApplicationProps.builder()
                    .env(envAlpha)
                    .envPipeline(envPipeline)
                    .stackName(appName+"-app-alpha")
                    .build()
                );
        }
        if( envPipeline.equals(envBeta) ){
            System.out.println("Env BETA is in the same account");
            betaService = new Application(
                app, 
                appName,
                StageConfig.DEPLOY_LINEAR_10_PERCENT_EVERY_1_MINUTES,
                EnvType.BETA,
                StackProps.builder()
                    .env(envBeta)
                    .stackName(appName+"-app-beta")
                    .build());   
        }else{
            System.out.println("Env BETA is in a cross account");
            betaService   =   new CrossAccountApplication(
                app,
                appName,
                StageConfig.DEPLOY_LINEAR_10_PERCENT_EVERY_1_MINUTES,
                EnvType.BETA,
                CrossAccountApplicationProps.builder()
                    .env(envBeta)
                    .envPipeline(envPipeline)
                    .stackName(appName+"-app-beta")
                    .build()
                );
        }

        Pipeline pipeline   =   new Pipeline(app, 
            appName+"-pipeline", 
            PipelineStackProps.builder()
                .appName(appName)
                .env(envPipeline)
                .gitRepo(git.getGitRepository())
                .deploymentConfigs(new StageConfig[]{ 
                    alphaService.getDeploymentConfig(), 
                    betaService.getDeploymentConfig(), 
                })
                .build());  

        pipeline.addDependency(git);
        pipeline.addDependency((Stack)alphaService);        
        pipeline.addDependency((Stack)betaService);
        app.synth();
    }
}
// eu comentei duas role names dentro da ECS Task. Testar para saber se vai impactar em alguma coisa.