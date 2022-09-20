package com.example;

import java.util.Arrays;
import java.util.List;

import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Stage;
import software.amazon.awscdk.pipelines.CodeCommitSourceOptions;
import software.amazon.awscdk.pipelines.CodePipeline;
import software.amazon.awscdk.pipelines.CodePipelineActionFactoryResult;
import software.amazon.awscdk.pipelines.CodePipelineSource;
import software.amazon.awscdk.pipelines.FileSet;
import software.amazon.awscdk.pipelines.ICodePipelineActionFactory;
import software.amazon.awscdk.pipelines.ProduceActionOptions;
import software.amazon.awscdk.pipelines.ShellStep;
import software.amazon.awscdk.pipelines.StageDeployment;
import software.amazon.awscdk.pipelines.Step;
import software.amazon.awscdk.services.codecommit.Repository;
import software.amazon.awscdk.services.codedeploy.IEcsDeploymentGroup;
import software.amazon.awscdk.services.codepipeline.Artifact;
import software.amazon.awscdk.services.codepipeline.IStage;
import software.amazon.awscdk.services.codepipeline.actions.CodeCommitTrigger;
import software.amazon.awscdk.services.codepipeline.actions.CodeDeployEcsContainerImageInput;
import software.amazon.awscdk.services.codepipeline.actions.CodeDeployEcsDeployAction;
import software.amazon.awscdk.services.iam.IRole;
import software.constructs.Construct;

public class BlueGreenPipeline extends Construct {

    private Construct scope =   null;
    
    public BlueGreenPipeline(Construct scope, final String id, final String appName, final String gitRepo, final List<BlueGreenConfig> stages){

        super(scope,id);
        this.scope = scope;

        CodePipeline pipeline   =   createPipeline(
            appName, 
            gitRepo);  

        stages.forEach(dc-> configureDeployStage(
                dc, 
                pipeline, 
                appName));
    }


    CodePipeline createPipeline(final String appName, String repo){

        CodePipelineSource  source  =   CodePipelineSource.codeCommit(
            Repository.fromRepositoryName(scope, "codecommit-repository", repo ),
            BlueGreenConfig.CODECOMMIT_BRANCH,
            CodeCommitSourceOptions
                .builder()
                .trigger(CodeCommitTrigger.POLL)
                .build());   

        // this.source = source;
        
        return CodePipeline.Builder.create(scope, appName+"-codepipeline")
            .publishAssetsInParallel(Boolean.FALSE)
            .dockerEnabledForSelfMutation(Boolean.TRUE)
            .crossAccountKeys(Boolean.TRUE)
            .synth(ShellStep.Builder.create(appName+"-synth")
                .input(source)
                .installCommands(Arrays.asList(
                    "npm install"))
                .commands(Arrays.asList(
                    "mvn -B clean package",
                    "npx cdk synth"))
                .build())
            .build(); 
    }    

    private void configureDeployStage(BlueGreenConfig deployConfig, CodePipeline pipeline, String appName){
   
        final String stageName =   deployConfig.getStageName();
        ShellStep codeBuildPre = ShellStep.Builder.create("ConfigureBlueGreenDeploy")
            .input(pipeline.getCloudAssemblyFileSet())
            .primaryOutputDirectory("codedeploy")          
            .commands(configureCodeDeploy(appName, deployConfig ))
            .build();

        Stage stage = Stage.Builder.create(scope, "Deploy-"+stageName).build();

        //CDK will use an inheritance mechanism implemented by the scoping system
        // to associate this stack with the deploy stage
        new ExampleComponent(
            stage, 
            appName+"-api-"+deployConfig.getStageName().toLowerCase(),
            appName,
            deployConfig.getDeployConfig(),
            StackProps.builder()
                .stackName(appName+deployConfig.getStageName())
                .description("Microservice "+appName+"-"+deployConfig.getStageName().toLowerCase())
                .env(deployConfig.getEnv())
                .build());

        StageDeployment stageDeployment = pipeline.addStage(stage);
        
        stageDeployment.addPre(codeBuildPre);
        stageDeployment.addPost(new BlueGreenPipeline.CodeDeployStep(
            "codeDeploy"+stageName.toLowerCase(), 
            stageName.toLowerCase(),
            codeBuildPre.getPrimaryOutput(), 
            deployConfig));
    }
    
    /**
     * Configures appspec.yaml, taskdef.json and imageDetails.json using information coming from the cdk.out (.assets files)
     * @param appName
     * @param targetEnv
     * @param stageNumber
     * @return
     */
    private List<String> configureCodeDeploy(final String appName, final BlueGreenConfig deploymentConfig){

        final String stageName =   deploymentConfig.getStageName();
        final String account =   deploymentConfig.getEnv().getAccount();
        final String region =   deploymentConfig.getEnv().getRegion();
        return Arrays.asList(

            "ls -l",
            "ls -l codedeploy",
            "repo_name=$(cat *-"+stageName+"/*.assets.json | jq -r '.dockerImages[] | .destinations[] | .repositoryName' | head -1)",
            "tag_name=$(cat *-"+stageName+"/*.assets.json | jq -r '.dockerImages | keys[0]')",
            "echo ${repo_name}",
            "echo ${tag_name}",
            "printf '{\"ImageURI\":\"%s\"}' \""+account+".dkr.ecr."+region+".amazonaws.com/${repo_name}:${tag_name}\" > codedeploy/imageDetail.json",                    
            "sed 's#APPLICATION#"+appName+"#g' codedeploy/template-appspec.yaml > codedeploy/appspec.yaml",
            "sed 's#APPLICATION#"+appName+"#g' codedeploy/template-taskdef.json | sed 's#TASK_EXEC_ROLE#"+"arn:aws:iam::"+account+":role/"+appName+"-"+stageName+"#g' | sed 's#fargate-task-definition#"+appName+"#g' > codedeploy/taskdef.json",
            "cat codedeploy/appspec.yaml",
            "cat codedeploy/taskdef.json",
            "cat codedeploy/imageDetail.json"
        );     
    }   
       
    static class CodeDeployStep extends Step implements ICodePipelineActionFactory{

        FileSet fileSet =   null;
        IRole codeDeployRole    =   null;
        IEcsDeploymentGroup dg  =   null;
        String envType  =   null;

        public CodeDeployStep(String id, String envType, FileSet fileSet, BlueGreenConfig deploymentConfig){
            super(id);
            this.fileSet    =   fileSet;
            this.codeDeployRole =   deploymentConfig.getCodeDeployRole();
            if(deploymentConfig.getEcsDeploymentGroup() == null ){
                throw new IllegalArgumentException("EcsDeploymentGroup cannot be null");
            }
            this.dg    =   deploymentConfig.getEcsDeploymentGroup();
            this.envType = envType;
        }

        @Override
        public  CodePipelineActionFactoryResult produceAction( IStage stage, ProduceActionOptions options) {

            Artifact artifact   =   options.getArtifacts().toCodePipeline(fileSet);           

            stage.addAction(CodeDeployEcsDeployAction.Builder.create()
                .actionName("Deploy")
                .role(codeDeployRole) 
                .appSpecTemplateInput(artifact)
                .taskDefinitionTemplateInput(artifact)
                .runOrder(options.getRunOrder())
                .containerImageInputs(Arrays.asList(CodeDeployEcsContainerImageInput.builder()
                    .input(artifact)
                    .taskDefinitionPlaceholder("IMAGE1_NAME")
                    .build()))
                .deploymentGroup( dg )
                .variablesNamespace("deployment-"+envType)
                .build());

            return CodePipelineActionFactoryResult.builder().runOrdersConsumed(1).build();
        }
    }    
}