package com.example.iac;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.constructs.Construct;

public class HelloWorldApp {
    static class SampleService extends Construct{

        SampleService(Construct scope, String id, String appName) throws Exception {
            super(scope, id);
            StackProps props    =   StackProps.builder().env(HelloWorldApp.makeEnv(null, null)).build();
            //Runtime
            ECSPlane ecs    =   new ECSPlane(scope, 
                                            appName+"-ecs", 
                                            appName, 
                                            props);
                                            
            // Pipeline pipeline   =   new Pipeline(scope, 
            //                                 appName+"-pipeline", 
            //                                 appName, 
            //                                 "arn:aws:elasticloadbalancing:us-east-1:742584497250:listener/app/hello-world-alb/ecb1a1368c079928/5e4920ca0e1f5c09", 
            //                                 "arn:aws:elasticloadbalancing:us-east-1:742584497250:listener/app/hello-world-alb/ecb1a1368c079928/cc2edd175c5d7400", 
            //                                 "hello-world-Blue", 
            //                                 "hello-world-Green", 
            //                                 props);

            Pipeline pipeline   =   new Pipeline(scope, 
                                                appName+"-pipeline", 
                                                appName, 
                                                ecs,
                                                props);

            CodeDeployConfigurator deployConfigurator  =    new CodeDeployConfigurator(scope, 
                                                appName+"-deploy-configurator", 
                                                appName, 
                                                pipeline, 
                                                props);

            // pipeline.addDependency(ecs);
            deployConfigurator.addDependency(pipeline);

            //create the ECS using the deployment type like described in this workshop...
            //https://cicd-pipeline-cdk-eks-bluegreen.workshop.aws/en/ecsbg/service.html      
        }
    }

    public static void main(String args[]) throws Exception{

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
