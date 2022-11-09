package com.example.toolchain;

import static com.example.Constants.APP_NAME;

import java.util.Arrays;
import java.util.List;

import com.example.cdk_fargate_bg.CdkFargateBg;

import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Stage;
import software.amazon.awscdk.pipelines.CodeCommitSourceOptions;
import software.amazon.awscdk.pipelines.CodePipeline;
import software.amazon.awscdk.pipelines.CodePipelineSource;
import software.amazon.awscdk.pipelines.ShellStep;
import software.amazon.awscdk.pipelines.StageDeployment;
import software.amazon.awscdk.pipelines.Step;
import software.amazon.awscdk.services.codecommit.Repository;
import software.amazon.awscdk.services.codepipeline.actions.CodeCommitTrigger;
import software.constructs.Construct;

public class Pipeline extends Construct {

    private CodePipeline pipeline   =   null;

    public Pipeline(Construct scope, final String id, final String gitRepoURL, final String gitBranch){

        super(scope,id);

        pipeline   =   createPipeline(
            gitRepoURL,
            gitBranch);
    }

    public Pipeline addStage(final String stageName, final String deployConfig, String account, String region) {

        DeployConfig config   =   DeployConfig.createDeploymentConfig(this, stageName, deployConfig, account, region);

        //The stage
        Stage deployStage = Stage.Builder.create(pipeline, "Deploy-"+stageName).env(config.getEnv()).build();

        //My stack
        new CdkFargateBg(
            deployStage, 
            "CdkFargateBg"+stageName,
            deployConfig,
            StackProps.builder()
                .stackName(APP_NAME+stageName)
                .description(APP_NAME+"-"+stageName)
                .build());

        //Configure AWS CodeDeploy
        Step configCodeDeployStep = ShellStep.Builder.create("ConfigureBlueGreenDeploy")
        .input(pipeline.getCloudAssemblyFileSet())
        .primaryOutputDirectory("codedeploy")    
        .commands(configureCodeDeploy( stageName, deployStage.getAccount(), deployStage.getRegion() ))
        .build(); 

        StageDeployment stageDeployment = pipeline.addStage(deployStage);
        stageDeployment.addPre(configCodeDeployStep);

        // IRole codeDeployRole  = Role.fromRoleArn(
        //     pipeline, 
        //     "AWSCodeDeployRole"+stageName, 
        //     "arn:aws:iam::"+deployStage.getAccount()+":role/"+CodeDeployBootstrap.getRoleName());

        // how to change the environment of the deploymentGroup? 
        // Only way is to load it from within a different stack that carries a scope with a different environment associated
        // Either I use the DeployConfig approach or I create the constructs IEcsDeploymentGroup and IRole but I need to figure out how to load them in an environment specific way

        // IEcsDeploymentGroup deploymentGroup  =  EcsDeploymentGroup.fromEcsDeploymentGroupAttributes(
        //         pipeline, 
        //         Constants.APP_NAME+"-DeploymentGroup", 
        //         EcsDeploymentGroupAttributes.builder()
        //             .deploymentGroupName( Constants.APP_NAME+"-"+stageName)
        //             .application(EcsApplication.fromEcsApplicationName(
        //                 pipeline, 
        //                 Constants.APP_NAME+"-ecs-deploy-app", 
        //                 Constants.APP_NAME+"-PreProd"))
        //             .build());
 
        //Deploy using AWS CodeDeploy
        stageDeployment.addPost(
            new CodeDeployStep(            
            "codeDeploypreprod", 
            "preprod",
            configCodeDeployStep.getPrimaryOutput(), 
            config.getCodeDeployRole(),
            config.getEcsDeploymentGroup())
        );    

        return this;
    }

    private List<String> configureCodeDeploy(String stageName, String account, String region ){

        return Arrays.asList(

            "ls -l",
            "ls -l codedeploy",
            "repo_name=$(cat *-"+stageName+"/*.assets.json | jq -r '.dockerImages[] | .destinations[] | .repositoryName' | head -1)",
            "tag_name=$(cat *-"+stageName+"/*.assets.json | jq -r '.dockerImages | keys[0]')",
            "echo ${repo_name}",
            "echo ${tag_name}",
            "printf '{\"ImageURI\":\"%s\"}' \""+account+".dkr.ecr."+region+".amazonaws.com/${repo_name}:${tag_name}\" > codedeploy/imageDetail.json",                    
            "sed 's#APPLICATION#"+APP_NAME+"#g' codedeploy/template-appspec.yaml > codedeploy/appspec.yaml",
            "sed 's#APPLICATION#"+APP_NAME+"#g' codedeploy/template-taskdef.json | sed 's#TASK_EXEC_ROLE#"+"arn:aws:iam::"+account+":role/"+APP_NAME+"-"+stageName+"#g' | sed 's#fargate-task-definition#"+APP_NAME+"#g' > codedeploy/taskdef.json",
            "cat codedeploy/appspec.yaml",
            "cat codedeploy/taskdef.json",
            "cat codedeploy/imageDetail.json"
        );     
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
}