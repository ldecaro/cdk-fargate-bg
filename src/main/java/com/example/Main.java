package com.example;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

/**
 * This reference pipeline deploys a greeting microservice into an ECS Fargate cluster using
 * AWS CodePipeline, AWS CodeBuild and AWS CodeDeploy. It is possible to create ECS environments
 * using one or more accounts depending on the use of Application or CrossAccountApplication.
 */
public class Main {

    public static void main(String args[]) throws Exception{

        App  app = new App();

        String appName = (String)app.getNode().tryGetContext("appName");
        if( appName == null || "".equals(appName.trim()) || "undefined".equals(appName)){
            appName = "ecs-microservice";
        }

        //if necessary, pack directory to upload
        final String buildNumber = System.getenv("CODEBUILD_BUILD_NUMBER");
        Boolean IS_CREATING  =   buildNumber == null ? Boolean.TRUE : Boolean.FALSE;
        if( IS_CREATING ){
            Util.createSrcZip(appName);
        }

        Environment envToolchain =   Util.makeEnv();
        System.out.println("Toolchain env: "+envToolchain.getAccount()+"/"+envToolchain.getRegion());

        //deploying the stacks...                                 
        Repository git    =   new Repository(app, 
            appName+"-git", 
            appName,
            IS_CREATING,
            StackProps.builder()
                .env(envToolchain)
                .terminationProtection(Boolean.FALSE)
                .build());

        new Toolchain(app, 
            appName+"-toolchain", 
            ToolchainStackProps.builder()
                .appName(appName)
                .env(envToolchain)
                .gitRepo(git.getGitRepository())
                .build());  


        app.synth();
    }
}