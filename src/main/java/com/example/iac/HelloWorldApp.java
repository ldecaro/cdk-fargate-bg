package com.example.iac;

import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;
import software.constructs.Construct;

public class HelloWorldApp {
    static class SampleService extends Construct{

        SampleService(Construct scope, String id, String appName) throws Exception {
            super(scope, id);
            StackProps props    =   StackProps.builder().env(Util.makeEnv(null, null)).build();

            ECSPlane ecs    =   new ECSPlane(scope, 
                                            appName+"-ecs", 
                                            appName, 
                                            props);

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

            pipeline.addDependency(ecs);
            deployConfigurator.addDependency(pipeline);     
        }
    }

    public static void main(String args[]) throws Exception{

        App  app = new App();
        new SampleService(app, "sample-app", "hello-world");
        app.synth();
    }
}
