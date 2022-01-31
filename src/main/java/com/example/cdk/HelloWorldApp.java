package com.example.cdk;

import com.example.cdk.PipelineStack.PipelineStackProps;
import com.example.iac.Util;

import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;

public class HelloWorldApp {

    public static void main(String args[]) throws Exception{

        App  app = new App();

        String appName = (String)app.getNode().tryGetContext("appName");
        if( appName == null || "".equals(appName.trim() )){
            appName = "ecs-microservice";
        }

        System.out.println("Microservice Name: "+appName);
        StackProps props    =   StackProps.builder().env(Util.makeEnv(null, null)).build();

        final String buildNumber = (String)app.getNode().tryGetContext("buildNumber");
        Boolean UPLOAD_PROJECT  =   buildNumber == null ? Boolean.TRUE : Boolean.FALSE;
        if( UPLOAD_PROJECT ){
            Util.createSrcZip(appName);
        }
              
        //deploying the stacks...                                 
        GitStack git    =   new GitStack(app, 
            appName+"-git", 
            appName,
            UPLOAD_PROJECT,
            StackProps.builder()
                .env(props.getEnv())
                .terminationProtection(Boolean.FALSE)
                .build());

        PipelineStack pipeline   =   new PipelineStack(app, 
            appName+"-pipeline", 
            PipelineStackProps.builder()
                .appName(appName)
                .env(props.getEnv())
                .gitRepo(git.getGitRepository())
                .build());  

        new ServiceAssetStack(app, appName+"-svc", appName, props);

        pipeline.addDependency(git);        

        app.synth();
    }
}
