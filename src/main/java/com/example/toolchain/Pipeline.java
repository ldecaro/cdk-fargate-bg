package com.example.toolchain;

import java.util.Arrays;
import java.util.List;

import com.example.Constants;
import com.example.webapp.WebApp;

import software.amazon.awscdk.Arn;
import software.amazon.awscdk.ArnComponents;
import software.amazon.awscdk.ArnFormat;
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
import software.amazon.awscdk.services.codedeploy.IEcsApplication;
import software.amazon.awscdk.services.codedeploy.IEcsDeploymentConfig;
import software.amazon.awscdk.services.codedeploy.IEcsDeploymentGroup;
import software.amazon.awscdk.services.codepipeline.actions.CodeCommitTrigger;
import software.constructs.Construct;

public class Pipeline extends Construct {

    public static final Boolean CONTINUOUS_DELIVERY       = Boolean.TRUE;
    public static final Boolean CONTINUOUS_DEPLOYMENT       = Boolean.FALSE;

    private String pipelineAccount  =   null;
    private CodePipeline pipeline   =   null;

    public Pipeline(Construct scope, final String id, final String gitRepoURL, final String gitBranch){

        super(scope,id);

        pipelineAccount = Stack.of(scope).getAccount();

        pipeline   =   createPipeline(
            gitRepoURL,
            gitBranch);
    }

    public Pipeline addStage(final String stageName,  final IEcsDeploymentConfig deployConfig, String account, String region) {

        return addStage(stageName, deployConfig, account, region, Boolean.FALSE);
    }    

    public Pipeline addStage(final String stageName, final IEcsDeploymentConfig deployConfig, final String account, final String region, final Boolean ADD_APPROVAL ) {

        Environment env = Environment.builder().region(region).account(account).build();

        //The stage
        Stage deployStage = Stage.Builder.create(pipeline, stageName).env(env).build();


        // My stack
        new WebApp(
            deployStage, 
            "Component"+stageName,
            deployConfig,
            StackProps.builder()
                .stackName(Constants.APP_NAME+stageName)
                .description(Constants.APP_NAME+"-"+stageName)
                .build());                 

        //Configure AWS CodeDeploy
        Step configCodeDeployStep = ShellStep.Builder.create("ConfigureBlueGreenDeploy")
            .input(pipeline.getCloudAssemblyFileSet())
            .primaryOutputDirectory("codedeploy")    
            .commands(configureCodeDeploy(stageName, env.getAccount(), env.getRegion()))
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
            importCodeDeployDeploymentGroup(env, stageName),
            stageName)
        );

        if( pipelineAccount != account ){
            //we need to add permissions to the UpdatePipeline stage so that it can build assets. 
            //Ultimately, it will require access to the cdk-deploy role in te target account.
            grantCdkDeployPermissionsforAdditionalEnvironment(account);
        }
        
        return this;
    }

    private void grantCdkDeployPermissionsforAdditionalEnvironment(String account){

        // pipeline.getSelfMutationProject()
    }

/*     private IRole importCodeDeployRole(final Environment env, final String stageName){

        return  Role.fromRoleArn(
            this,
            "AWSCodeDeployRole"+stageName,
            Arn.format(ArnComponents.builder()
                .partition("aws")
                .region("") 
                .service("iam")
                .account(env.getAccount())
                .resource("role")
                .resourceName(Constants.CodeDeploy.ROLE_NAME_DEPLOY)
                .build()),
            FromRoleArnOptions.builder()
                .mutable(false)
                .build());
    } */

    private IEcsDeploymentGroup importCodeDeployDeploymentGroup(final Environment env, final String stageName){

        IEcsApplication codeDeployApp = EcsApplication.fromEcsApplicationArn(
            this, 
            Constants.APP_NAME+"-ecs-deploy-app", 
            Arn.format(ArnComponents.builder()
                .arnFormat(ArnFormat.COLON_RESOURCE_NAME)
                .partition("aws")
                .region(env.getRegion()) // IAM is global in each partition
                .service("codedeploy")
                .account(env.getAccount())
                .resource("application")
                .resourceName(Constants.APP_NAME+"-"+stageName)
                .build())
            );

        IEcsDeploymentGroup deploymentGroup = EcsDeploymentGroup.fromEcsDeploymentGroupAttributes(
            this, 
            Constants.APP_NAME+"-DeploymentGroup",
            EcsDeploymentGroupAttributes.builder()
                .deploymentGroupName(Constants.APP_NAME+"-"+stageName)
                .application(codeDeployApp)
                .build()
            );  

        return deploymentGroup;
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