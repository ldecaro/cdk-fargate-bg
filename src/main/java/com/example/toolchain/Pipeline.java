package com.example.toolchain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import com.example.Constants;
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

    //We can't have stage names that are substrings of other stage names as it would break instrumentation.
    private Collection<String> stageNames = new ArrayList<>();

    public Pipeline(Construct scope, final String id, final String gitRepoURL, final String gitBranch){

        super(scope,id);

        pipeline   =   createPipeline(
            gitRepoURL,
            gitBranch);
    }

    public Pipeline addStage(final String stageName, final String deployConfig, String account, String region) {

        validateStageName(stageName);

        DeployConfig config   =   DeployConfig.createDeploymentConfig(this, stageName, deployConfig, account, region);

        //The stage
        Stage deployStage = Stage.Builder.create(pipeline, stageName).env(config.getEnv()).build();

        //My stack
        new CdkFargateBg(
            deployStage, 
            "CdkFargateBg"+stageName,
            deployConfig,
            StackProps.builder()
                .stackName(Constants.APP_NAME+stageName)
                .description(Constants.APP_NAME+"-"+stageName)
                .build());

        //Configure AWS CodeDeploy
        Step configCodeDeployStep = ShellStep.Builder.create("ConfigureBlueGreenDeploy")
        .input(pipeline.getCloudAssemblyFileSet())
        .primaryOutputDirectory("codedeploy")    
        .commands(configureCodeDeploy( stageName, deployStage.getAccount(), deployStage.getRegion() ))
        .build(); 
 
        StageDeployment stageDeployment = pipeline.addStage(deployStage);

        stageDeployment.addPre(configCodeDeployStep);

        //Deploy using AWS CodeDeploy
        stageDeployment.addPost(
            new CodeDeployStep(            
            "codeDeploypreprod", 
            configCodeDeployStep.getPrimaryOutput(), 
            config)
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

    private boolean unsupportedStageName(final String stageName){

        return stageNames.stream().anyMatch(c-> c.indexOf(stageName) != -1 || stageName.indexOf(c)!= -1);
    }

    private void validateStageName(final String stageName){

        if( unsupportedStageName(stageName.toLowerCase()) ){
            System.out.println("The name of this stage is unsupported because it is a substring of another stage name (or another stage name is a substring of this stage name): "+stageName+". Please choose another stage name for "+stageName);
            throw new RuntimeException("The name of this stage is unsupported because it is a substring of another stage name: "+stageName);
        }else{
            stageNames.add(stageName.toLowerCase());
        }
    }    
}