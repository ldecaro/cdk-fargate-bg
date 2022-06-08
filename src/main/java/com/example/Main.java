package com.example;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

/**
 * The application includes a Git repository and a Toolchain. The Toolchain 
 * deploys the Greeting microservice into multiple environments using
 * AWS CodePipeline, AWS CodeBuild and AWS CodeDeploy. It supports
 * single-account or cross-account deployment models.
 * 
 * ./cdk-bootstrap-deploy-to.sh # before running the application.
 */
public class Main {

    public static void main(String args[]) throws Exception{

        App  app = new App();

        String appName = Util.appName();

        //if necessary, pack directory to upload
        final String buildNumber = System.getenv("CODEBUILD_BUILD_NUMBER");
        Boolean IS_CREATING  =   buildNumber == null ? Boolean.TRUE : Boolean.FALSE;
        if( IS_CREATING ){
            Util.createSrcZip(appName);
        }

        Environment envToolchain =   Util.toolchainEnv();
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

        new BootstrapCodeDeploy(app, 
            StackProps.builder()
                .env(Util.makeEnv())
                .stackName("AWSCodeDeployBootstrap")
                .description("This stack includes resources that are used by AWS CodeDeploy")
                .build());

        app.synth();
    }
}