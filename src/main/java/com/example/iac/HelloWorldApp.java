package com.example.iac;

import com.example.iac.PipelineStack.PipelineStackProps;

import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;
import software.constructs.Construct;

public class HelloWorldApp {
    static class SampleService extends Construct{

        SampleService(Construct scope, String id) throws Exception {
            
            super(scope, id);

            String appName      =   this.getNode().getAddr().substring(0, 10);
            System.out.println("AppName: "+appName);
            StackProps props    =   StackProps.builder().env(Util.makeEnv(null, null)).build();

            //creating the configuration files: appspec.yaml, buildspec.yml and taskdef.json
            (new Util()).updateConfigurationFiles(appName, props.getEnv().getAccount(), props.getEnv().getRegion(), appName);

            //pack
            Util.createSrcZip(appName);              
            
            //deploying the stacks...
            ECSStack ecs    =   new ECSStack(scope, 
                                                appName+"-ecs", 
                                                appName, 
                                                props);                                    

            GitStack git    =   new GitStack(scope, 
                                                appName+"-git", 
                                                appName, 
                                                StackProps.builder()
                                                    .env(props.getEnv())
                                                    .terminationProtection(Boolean.FALSE)
                                                    .build());

            PipelineStack pipeline   =   new PipelineStack(scope, 
                                                appName+"-pipeline", 
                                                PipelineStackProps.builder()
                                                    .appName(appName)
                                                    .env(props.getEnv())
                                                    .gitRepo(git.getGitRepository())
                                                    .listenerBlueArn(ecs.getListenerBlueArn())
                                                    .listenerGreenArn(ecs.getListenerGreenArn())
                                                    .tgBlueName(ecs.getTgBlueName())
                                                    .tgGreenName(ecs.getTgGreenName())
                                                    .build());
            
            BlueGreenStack deployConfigurator  =    new BlueGreenStack(scope, 
                                                        appName+"-deploy-configurator", 
                                                        appName, 
                                                        // git.getGitRepository(),
                                                        pipeline, 
                                                        props);

            pipeline.addDependency(git);
            pipeline.addDependency(ecs);
            deployConfigurator.addDependency(pipeline);     
        }
    }

    public static void main(String args[]) throws Exception{

        App  app = new App();
        new SampleService(app, "hello-world");
        app.synth();
    }
}
