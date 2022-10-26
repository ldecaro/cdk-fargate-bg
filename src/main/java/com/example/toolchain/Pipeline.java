package com.example.toolchain;

import java.util.Arrays;
import java.util.List;

import com.example.App;
import com.example.Constants;
import com.example.bootstrap.CodeDeployBootstrap;
import com.example.cdk_fargate_bg.CdkFargateBg;

import static com.example.Constants.APP_NAME;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Stage;
import software.amazon.awscdk.StageProps;
import software.amazon.awscdk.pipelines.CodeCommitSourceOptions;
import software.amazon.awscdk.pipelines.CodePipeline;
import software.amazon.awscdk.pipelines.CodePipelineSource;
import software.amazon.awscdk.pipelines.ShellStep;
import software.amazon.awscdk.pipelines.StageDeployment;
import software.amazon.awscdk.pipelines.Step;
import software.amazon.awscdk.services.codecommit.Repository;
import software.amazon.awscdk.services.codedeploy.EcsApplication;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentGroup;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentGroupAttributes;
import software.amazon.awscdk.services.codedeploy.IEcsDeploymentGroup;
import software.amazon.awscdk.services.codepipeline.actions.CodeCommitTrigger;
import software.amazon.awscdk.services.iam.IRole;
import software.amazon.awscdk.services.iam.Role;
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

        Stage deployStage = Stage.Builder.create(pipeline, "Deploy-PreProd").env(preProdEnv).build();

        new CdkFargateBg(
            deployStage, 
            "CdkFargateBgPreProd",
            "CodeDeployDefault.ECSLinear10PercentEvery3Minutes",
            StackProps.builder()
                .stackName("ExampleMicroservicePreProd")
                .description("Microservice ExampleMicroserice-PreProd")
                .build());


        Step configCodeDeployStep = ShellStep.Builder.create("ConfigureBlueGreenDeploy")
        .input(pipeline.getCloudAssemblyFileSet())
        .primaryOutputDirectory("codedeploy")    
        .commands(configureCodeDeploy( "PreProd", deployStage.getAccount(), deployStage.getRegion() ))
        .build(); 
        
        StageDeployment stageDeployment = pipeline.addStage(deployStage);
        stageDeployment.addPre(configCodeDeployStep);

        IRole codeDeployRole  = Role.fromRoleArn(
            pipeline, 
            "AWSCodeDeployRolePreProd", 
            "arn:aws:iam::"+deployStage.getAccount()+":role/"+CodeDeployBootstrap.getRoleName());

        IEcsDeploymentGroup deploymentGroup  =  EcsDeploymentGroup.fromEcsDeploymentGroupAttributes(
                pipeline, 
                Constants.APP_NAME+"-DeploymentGroup", 
                EcsDeploymentGroupAttributes.builder()
                    .deploymentGroupName( Constants.APP_NAME+"-"+deployStage.getStageName() )
                    .application(EcsApplication.fromEcsApplicationName(
                        pipeline, 
                        Constants.APP_NAME+"-ecs-deploy-app", 
                        Constants.APP_NAME+"-"+deployStage.getStageName()))
                    .build());          

        stageDeployment.addPost(
            new NewCodeDeployStep(            
            "codeDeploypreprod", 
            "preprod",
            configCodeDeployStep.getPrimaryOutput(), 
            codeDeployRole,
            deploymentGroup)
        );
        


        // DeployConfig preProd = DeployConfig.createDeploymentConfig(
        //     this, 
        //     "PreProd", 
        //     "CodeDeployDefault.ECSLinear10PercentEvery3Minutes",
        //     preProdEnv);
        
        // Arrays.asList(
        //     new DeployConfig[]{preProd}).forEach(
        //         deployConfig->configureDeployStage(pipeline, deployConfig));
    }

    private List<String> configureCodeDeploy(String stageName, String account, String region ){

        // if( deployConfig == null ){
        //     return Arrays.asList(new String[]{});
        // }
        // final String stageName =   deployConfig.getStageName();        
        // final String account =  deployConfig.getAccount();
        // final String region =   deployConfig.getRegion();
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