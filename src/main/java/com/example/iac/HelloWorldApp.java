package com.example.iac;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.constructs.Construct;

public class HelloWorldApp {
    

    static class SampleService extends Construct{

        SampleService(Construct scope, String id, String appName){
            super(scope, id);
            StackProps props    =   StackProps.builder().env(HelloWorldApp.makeEnv(null, null)).build();
            //Runtime
            ECSPlane ecs    =   new ECSPlane(scope, appName+"-ECSPlane", appName, props);
            
                //TODO create the ECS using the deployment type like described in this workshop...
                //https://cicd-pipeline-cdk-eks-bluegreen.workshop.aws/en/ecsbg/service.html
            Pipeline pipeline   =   new Pipeline(scope, appName+"-ECSPipeline", appName, props);
        }

    }

    public static void main(String argsp[]){

        App  app = new App();
        new SampleService(app, "sample-app", "hello-world");
        app.synth();
    }

    // Helper method to build an environment
    static Environment makeEnv(String account, String region) {
        account = (account == null) ? System.getenv("CDK_DEFAULT_ACCOUNT") : account;
        region = (region == null) ? System.getenv("CDK_DEFAULT_REGION") : region;
		//System.out.println("Using Account-Region: "+ account+"-"+region);
        return Environment.builder()
                .account(account)
                .region(region)
                .build();
    }    
}
