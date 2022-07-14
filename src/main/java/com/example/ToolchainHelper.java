package com.example;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.jetbrains.annotations.NotNull;

import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.pipelines.CodeCommitSourceOptions;
import software.amazon.awscdk.pipelines.CodePipeline;
import software.amazon.awscdk.pipelines.CodePipelineActionFactoryResult;
import software.amazon.awscdk.pipelines.CodePipelineSource;
import software.amazon.awscdk.pipelines.FileSet;
import software.amazon.awscdk.pipelines.ICodePipelineActionFactory;
import software.amazon.awscdk.pipelines.IFileSetProducer;
import software.amazon.awscdk.pipelines.ProduceActionOptions;
import software.amazon.awscdk.pipelines.ShellStep;
import software.amazon.awscdk.pipelines.StageDeployment;
import software.amazon.awscdk.pipelines.Step;
import software.amazon.awscdk.services.codecommit.IRepository;
import software.amazon.awscdk.services.codedeploy.IEcsDeploymentGroup;
import software.amazon.awscdk.services.codepipeline.Artifact;
import software.amazon.awscdk.services.codepipeline.IStage;
import software.amazon.awscdk.services.codepipeline.actions.CodeCommitTrigger;
import software.amazon.awscdk.services.codepipeline.actions.CodeDeployEcsContainerImageInput;
import software.amazon.awscdk.services.codepipeline.actions.CodeDeployEcsDeployAction;
import software.amazon.awscdk.services.iam.IRole;
import software.constructs.Construct;

public class ToolchainHelper {

    private Construct scope =   null;

    CodePipelineSource source   =   null;

    public ToolchainHelper(Construct scope){
        this.scope  =   scope;
    }

    CodePipeline createPipeline(final String appName, IRepository repo){

        CodePipelineSource  source  =   CodePipelineSource.codeCommit(
            repo,
            "main",
            CodeCommitSourceOptions
                .builder()
                .trigger(CodeCommitTrigger.POLL)
                .build());   

        this.source = source;
        
        return CodePipeline.Builder.create(scope, appName+"-codepipeline")
        .selfMutation(Boolean.TRUE)
        .crossAccountKeys(Boolean.TRUE)
        .publishAssetsInParallel(Boolean.FALSE)
        .dockerEnabledForSelfMutation(Boolean.TRUE)
        .synth(ShellStep.Builder.create(appName+"-synth")
            .input(source)
            .installCommands(Arrays.asList(
                "npm install -g aws-cdk@2.31.1"
                ))
            .commands(Arrays.asList(
                "mvn -B clean package",
                "cd target && ls -d  */ | xargs rm -rf && ls -lah && cd .. ",
                "cdk synth"))
            .build())
        .build(); 
    }      

    /**
     * Configures appspec.yaml, taskdef.json and imageDetails.json using information coming from the cdk.out (.assets files)
     * @param appName
     * @param targetEnv
     * @param stageNumber
     * @return
     */
    List<String> configureCodeDeploy(String appName, DeploymentConfig deploymentConfig){

        final String strEnvType =   deploymentConfig.getEnvType().getType();
        final String account =   deploymentConfig.getEnv().getAccount();
        final String region =   deploymentConfig.getEnv().getRegion();

        return Arrays.asList(

            "mkdir codedeploy",
            "ls -l",
            "export REPO_NAME=$(cat *"+strEnvType+"/*.assets.json | jq -r '.dockerImages[] | .destinations[] | .repositoryName' | head -1)",
            "export TAG_NAME=$(cat *"+strEnvType+"/*.assets.json | jq -r '.dockerImages | keys[0]')",
            "echo $REPO_NAME",
            "echo $TAG_NAME",
            "printf '{\"ImageURI\":\"%s\"}' \""+account+".dkr.ecr."+region+".amazonaws.com/$REPO_NAME:$TAG_NAME\" > codedeploy/imageDetail.json",                    
            "sed 's#APPLICATION#"+appName+"#g' ../source/codedeploy/template-appspec.yaml >> codedeploy/appspec.yaml",
            "sed 's#APPLICATION#"+appName+"#g' ../source/codedeploy/template-taskdef.json | sed 's#TASK_EXEC_ROLE#"+"arn:aws:iam::"+account+":role/"+appName+"-"+strEnvType.toLowerCase()+"#g' | sed 's#fargate-task-definition#"+appName+"#g' >> codedeploy/taskdef.json",
            "cat codedeploy/appspec.yaml",
            "cat codedeploy/taskdef.json",
            "cat codedeploy/imageDetail.json"
        );     
    }

    DeploymentConfig createDeploymentConfig(DeploymentConfig.EnvType envType, String deploymentConfig, final ToolchainStackProps props){

        Environment env     =   Util.getEnv(envType);

        return new DeploymentConfig(
            scope, 
            props.getAppName(),
            deploymentConfig,
            envType,
            StackProps.builder()
                .env(env)
                .stackName(props.getAppName()+"-svc-"+envType.toString().toLowerCase())
                .build());          
    }    
    
    public CodePipelineSource getCodePipelineSource(){
        return this.source;
    }

    static class CodeDeployStep extends Step implements ICodePipelineActionFactory{

        FileSet fileSet;
        IRole codeDeployRole;
        IEcsDeploymentGroup dg;
        String envType;

        public CodeDeployStep(String id, String envType, FileSet fileSet, DeploymentConfig deploymentConfig){
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
        public @NotNull CodePipelineActionFactoryResult produceAction(@NotNull IStage stage, @NotNull ProduceActionOptions options) {

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

    void configureDeployStage(DeploymentConfig deployConfig, CodePipeline pipeline, CodePipelineSource source, ToolchainStackProps props){
   
        final String strEnvType =   deployConfig.getEnvType().toString().toLowerCase();
        ShellStep codeBuildPre = ShellStep.Builder.create("ConfigureBlueGreenDeploy")
            .input(pipeline.getCloudAssemblyFileSet())
            .additionalInputs(new HashMap<String,IFileSetProducer>(){{
                put("../source", source);
            }})
            .primaryOutputDirectory("codedeploy")          
            .commands(configureCodeDeploy(props.getAppName(), deployConfig ))
            .build();    

        Deployment deploy = new Deployment(scope, 
            "Deploy-"+deployConfig.getEnvType().getType(), 
            props.getAppName(),
            deployConfig);

        StageDeployment stageDeployment = pipeline.addStage(deploy);
        
        stageDeployment.addPre(codeBuildPre);
        stageDeployment.addPost(new ToolchainHelper.CodeDeployStep(
            "codeDeploy"+deployConfig.getEnvType().toString(), 
            strEnvType,
            codeBuildPre.getPrimaryOutput(), 
            deployConfig));
    }
}
