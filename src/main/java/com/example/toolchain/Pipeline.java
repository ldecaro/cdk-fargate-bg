package com.example.toolchain;

import static com.example.Constants.APP_NAME;

import java.util.Arrays;

import com.example.App;

import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StageProps;
import software.amazon.awscdk.pipelines.CodeCommitSourceOptions;
import software.amazon.awscdk.pipelines.CodePipeline;
import software.amazon.awscdk.pipelines.CodePipelineSource;
import software.amazon.awscdk.pipelines.ShellStep;
import software.amazon.awscdk.pipelines.StageDeployment;
import software.amazon.awscdk.services.codecommit.Repository;
import software.amazon.awscdk.services.codepipeline.actions.CodeCommitTrigger;
import software.constructs.Construct;

public class Pipeline extends Construct {

    private Environment preProdEnv = Environment.builder()
        .account(App.TOOLCHAIN_ACCOUNT)
        .region(App.TOOLCHAIN_REGION)
        .build();

    public Pipeline(Construct scope, final String id, final String gitRepoURL, final String gitBranch){

        super(scope,id);

        CodePipeline pipeline   =   createPipeline(
            gitRepoURL,
            gitBranch);  

        DeployConfig preProd = DeployConfig.createDeploymentConfig(
            this, 
            "PreProd", 
            "CodeDeployDefault.ECSLinear10PercentEvery3Minutes",
            preProdEnv);
        
        Arrays.asList(
            new DeployConfig[]{preProd}).forEach(
                deployConfig->configureDeployStage(pipeline, deployConfig));
    }

    CodePipeline createPipeline(String repoURL, String branch){

        CodePipelineSource  source  =   CodePipelineSource.codeCommit(
            Repository.fromRepositoryName(this, "code-repository", repoURL ),
            branch,
            CodeCommitSourceOptions
                .builder()
                .trigger(CodeCommitTrigger.POLL)
                .build());   
        
        return CodePipeline.Builder.create(this, "Pipeline-"+APP_NAME)
            .publishAssetsInParallel(Boolean.FALSE)
            .dockerEnabledForSelfMutation(Boolean.TRUE)
            .crossAccountKeys(Boolean.TRUE)
            .synth(ShellStep.Builder.create(APP_NAME+"-synth")
                .input(source)
                .installCommands(Arrays.asList(
                    "npm install"))
                .commands(Arrays.asList(
                    "mvn -B clean package",
                    "npx cdk synth"))
                .build())
            .build();
    }  

    private void configureDeployStage(CodePipeline pipeline, DeployConfig deployConfig){       

        final String stageName = deployConfig.getStageName();

        DeployStage stage = new DeployStage(
            pipeline, 
            "Deploy-"+stageName, 
            pipeline.getCloudAssemblyFileSet(), 
            deployConfig, 
            StageProps.builder()
                .env(deployConfig.getEnv())
                .build());

        StageDeployment stageDeployment = pipeline.addStage(stage);

        stageDeployment.addPre(stage.getConfigCodeDeployStep());
        stageDeployment.addPost(stage.getCodeDeployStep());
    }    
}