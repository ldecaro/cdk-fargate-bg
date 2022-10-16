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

    public static final String COMPONENT_ACCOUNT          = App.TOOLCHAIN_ACCOUNT;
    public static final String COMPONENT_REGION           = App.TOOLCHAIN_REGION;

    private Construct scope =   null;
    public Pipeline(Construct scope, final String id, final String gitRepoURL, final String gitBranch){

        super(scope,id);
        this.scope = scope;

        CodePipeline pipeline   =   createPipeline(
            gitRepoURL,
            gitBranch);  

        DeployConfig preProd = DeployConfig.createDeploymentConfig(
            this, 
            "PreProd", 
            "CodeDeployDefault.ECSLinear10PercentEvery3Minutes",
                Environment.builder()
                    .account(Pipeline.COMPONENT_ACCOUNT)
                    .region(Pipeline.COMPONENT_REGION)
                .build()  
            );
        
        Arrays.asList(
            new DeployConfig[]{preProd}).forEach(
                deployConfig->configureDeployStage(deployConfig,pipeline));
    }

    CodePipeline createPipeline(String repoURL, String branch){

        CodePipelineSource  source  =   CodePipelineSource.codeCommit(
            Repository.fromRepositoryName(scope, "code-repository", repoURL ),
            branch,
            CodeCommitSourceOptions
                .builder()
                .trigger(CodeCommitTrigger.POLL)
                .build());   
        
        return CodePipeline.Builder.create(scope, "Pipeline-"+APP_NAME)
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

    private void configureDeployStage(DeployConfig deployConfig, CodePipeline pipeline){       

        final String stageName = deployConfig.getStageName();

        DeployStage stage = new DeployStage(
            scope, 
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