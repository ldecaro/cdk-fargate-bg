package com.example.toolchain;

import java.util.Arrays;
import java.util.List;

import com.example.Constants;
import com.example.bootstrap.CodeDeployBootstrap;
import com.example.cdk_fargate_bg.CdkFargateBg;

import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Stage;
import software.amazon.awscdk.pipelines.CodeCommitSourceOptions;
import software.amazon.awscdk.pipelines.CodePipeline;
import software.amazon.awscdk.pipelines.CodePipelineSource;
import software.amazon.awscdk.pipelines.ManualApprovalStep;
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

    public static final Boolean CONTINUOUS_DELIVERY       = Boolean.TRUE;
    public static final Boolean CONTINUOUS_DEPLOYMENT       = Boolean.FALSE;
    
    private CodePipeline pipeline   =   null;

    public Pipeline(Construct scope, final String id, final String gitRepoURL, final String gitBranch){

        super(scope,id);

        pipeline   =   createPipeline(
            gitRepoURL,
            gitBranch);
    }

    public Pipeline addStage(final String stageName, final String deployConfig, final String account, final String region, final Boolean ADD_APPROVAL ) {

        Environment env = Environment.builder().region(region).account(account).build();

        //The stage
        Stage deployStage = Stage.Builder.create(pipeline, stageName).env(env).build();

        //My stack
        new CdkFargateBg(
            deployStage, 
            "CdkFargateBg"+stageName,
            deployConfig,
            StackProps.builder()
                .stackName(Constants.APP_NAME+stageName)
                .description(Constants.APP_NAME+"-"+stageName)
                .build());

        IEcsDeploymentGroup deploymentGroup  =  EcsDeploymentGroup.fromEcsDeploymentGroupAttributes(
            //cannot associate the scope with the CdkFargateBg stack as dependencies cannot cross stage boundaries.
            new Stack(this, "ghost-stack-codedeploy-dg-"+stageName, StackProps.builder().env(env).build()), 
            // component,
            Constants.APP_NAME+"-DeploymentGroup", 
            EcsDeploymentGroupAttributes.builder()
                .deploymentGroupName( Constants.APP_NAME+"-"+stageName )
                .application(EcsApplication.fromEcsApplicationName(
                    new Stack(this, "ghost-stack-codedeploy-app-"+stageName, StackProps.builder().env(env).build()),
                    // component,
                    Constants.APP_NAME+"-ecs-deploy-app", 
                    Constants.APP_NAME+"-"+stageName))
                .build());  

        IRole codeDeployRole  = Role.fromRoleArn(
            new Stack(this, "ghost-stack-role-"+stageName, StackProps.builder().env(env).build()),
            // component,
            "AWSCodeDeployRole"+stageName, 
            "arn:aws:iam::"+deploymentGroup.getEnv().getAccount()+":role/"+CodeDeployBootstrap.getRoleName());                   

        //Configure AWS CodeDeploy
        Step configCodeDeployStep = ShellStep.Builder.create("ConfigureBlueGreenDeploy")
        .input(pipeline.getCloudAssemblyFileSet())
        .primaryOutputDirectory("codedeploy")    
        .commands(configureCodeDeploy( stageName, deployStage.getAccount(), deployStage.getRegion() ))
        .build(); 
 
        StageDeployment stageDeployment = pipeline.addStage(deployStage);

        if(ADD_APPROVAL){
            stageDeployment.addPre(ManualApprovalStep.Builder.create("Approve "+stageName).build(), configCodeDeployStep);
        }else{
            stageDeployment.addPre(configCodeDeployStep);
        }

        //Deploy using AWS CodeDeploy
        stageDeployment.addPost(
            new CodeDeployStep(            
            "codeDeploypreprod", 
            configCodeDeployStep.getPrimaryOutput(), 
            codeDeployRole,
            deploymentGroup,
            stageName)
        );
        return this;
    }

    public Pipeline addStage(final String stageName, final String deployConfig, String account, String region) {

        return addStage(stageName, deployConfig, account, region, Boolean.FALSE);
    }

    private List<String> configureCodeDeploy(final String stageName, String account, String region ){

        final String pipelineId    =   ((Construct)pipeline).getNode().getId();

        return Arrays.asList(

            "ls -l",
            "ls -l codedeploy",
            "repo_name=$(cat assembly*"+pipelineId+"-"+stageName+"/*.assets.json | jq -r '.dockerImages[] | .destinations[] | .repositoryName' | head -1)",
            "tag_name=$(cat assembly*"+pipelineId+"-"+stageName+"/*.assets.json | jq -r '.dockerImages | keys[0]')",
            "echo ${repo_name}",
            "echo ${tag_name}",
            "printf '{\"ImageURI\":\"%s\"}' \""+account+".dkr.ecr."+region+".amazonaws.com/${repo_name}:${tag_name}\" > codedeploy/imageDetail.json",                    
            "sed 's#APPLICATION#"+Constants.APP_NAME+"#g' codedeploy/template-appspec.yaml > codedeploy/appspec.yaml",
            "sed 's#APPLICATION#"+Constants.APP_NAME+"#g' codedeploy/template-taskdef.json | sed 's#TASK_EXEC_ROLE#"+"arn:aws:iam::"+account+":role/"+Constants.APP_NAME+"-"+stageName+"#g' | sed 's#fargate-task-definition#"+Constants.APP_NAME+"#g' > codedeploy/taskdef.json",
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
        
        return CodePipeline.Builder.create(this, "Pipeline-"+Constants.APP_NAME)
            .publishAssetsInParallel(Boolean.FALSE)
            .dockerEnabledForSelfMutation(Boolean.TRUE)
            .crossAccountKeys(Boolean.TRUE)
            .synth(ShellStep.Builder.create(Constants.APP_NAME+"-synth")
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