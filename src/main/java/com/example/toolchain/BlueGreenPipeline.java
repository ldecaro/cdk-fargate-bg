package com.example.toolchain;

import static com.example.Constants.APP_NAME;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.example.App;
import com.example.cdk_fargate_bg.CdkFargateBg;

import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Stage;
import software.amazon.awscdk.StageProps;
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

    public static final String COMPONENT_ACCOUNT          = App.TOOLCHAIN_ACCOUNT;
    public static final String COMPONENT_REGION           = App.TOOLCHAIN_REGION;

    private Construct scope =   null;
    public BlueGreenPipeline(Construct scope, final String id, final String gitRepoURL, final String gitBranch){

        super(scope,id);
        this.scope = scope;

        CodePipeline pipeline   =   createPipeline(
            gitRepoURL,
            gitBranch);  

        BlueGreenDeployConfig preProd = BlueGreenDeployConfig.createDeploymentConfig(
            (Construct)this, 
            "PreProd", 
            "CodeDeployDefault.ECSLinear10PercentEvery3Minutes",
                Environment.builder()
                    .account(BlueGreenPipeline.COMPONENT_ACCOUNT)
                    .region(BlueGreenPipeline.COMPONENT_REGION)
                .build()  
            );
        
        Arrays.asList(new BlueGreenDeployConfig[]{preProd}).forEach(c->configureDeployStage(c,pipeline));
    }

    CodePipeline createPipeline(String repoURL, String branch){

        CodePipelineSource  source  =   CodePipelineSource.codeCommit(
            Repository.fromRepositoryName(scope, "code-repository", repoURL ),
            branch,
            CodeCommitSourceOptions
                .builder()
                .trigger(CodeCommitTrigger.POLL)
                .build());   
        
        return CodePipeline.Builder.create(scope, APP_NAME+"-codepipeline")
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
    
    private class MyStage extends Stage {

        private Step codeDeployStep = null;
        public MyStage(Construct scope, String id, ShellStep codeBuildPre, BlueGreenDeployConfig deployConfig, StageProps stageProps){

            super(scope, id, stageProps);
            String stageName = deployConfig.getStageName();

            new CdkFargateBg(
                this, 
                "CdkFargateBg"+stageName,
                deployConfig.getDeployConfig(),
                StackProps.builder()
                    .stackName(APP_NAME+stageName)
                    .description("Microservice "+APP_NAME+"-"+stageName.toLowerCase())
                    .build());

            this.codeDeployStep = new CodeDeployStep(            
                "codeDeploy"+stageName.toLowerCase(), 
                stageName.toLowerCase(),
                codeBuildPre.getPrimaryOutput(), 
                deployConfig);
        }

        public Step getCodeDeployStep(){
            return codeDeployStep;
        }

        class CodeDeployStep extends Step implements ICodePipelineActionFactory{

            FileSet fileSet =   null;
            IRole codeDeployRole    =   null;
            IEcsDeploymentGroup dg  =   null;
            String envType  =   null;
    
            public CodeDeployStep(String id, String envType, FileSet fileSet, BlueGreenDeployConfig deploymentConfig){
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
            public  CodePipelineActionFactoryResult produceAction(IStage stage, ProduceActionOptions options) {
    
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

    private void configureDeployStage(BlueGreenDeployConfig deployConfig, CodePipeline pipeline){       

        final String stageName = deployConfig.getStageName();
        // Stage stage = Stage.Builder.create(scope, "Deploy-"+stageName).env(deployConfig.getEnv()).build();

        // //CDK will use an inheritance mechanism implemented by the scoping system
        // //to associate this stack with the deploy stage
        // new CdkFargateBg(
        //     stage, 
        //     "CdkFargateBg"+stageName,
        //     deployConfig.getDeployConfig(),
        //     StackProps.builder()
        //         .stackName(APP_NAME+stageName)
        //         .description("Microservice "+APP_NAME+"-"+stageName.toLowerCase())
        //         .build());

        // StageDeployment stageDeployment = pipeline.addStage(stage);
        
        ShellStep codeBuildPre = ShellStep.Builder.create("ConfigureBlueGreenDeploy")
            .input(pipeline.getCloudAssemblyFileSet())
            .primaryOutputDirectory("codedeploy")    
            .commands(configureCodeDeploy( deployConfig ))
            .build();

        MyStage stage = new MyStage(
            scope, 
            "Deploy-"+stageName, 
            codeBuildPre, 
            deployConfig, 
            StageProps.builder()
                .env(deployConfig.getEnv())
                .build());

        StageDeployment stageDeployment = pipeline.addStage(stage);

        stageDeployment.addPre(codeBuildPre);
        stageDeployment.addPost(stage.getCodeDeployStep());
        // stageDeployment.addPost(new BlueGreenPipeline.CodeDeployStep(
        //     "codeDeploy"+stageName.toLowerCase(), 
        //     stageName.toLowerCase(),
        //     codeBuildPre.getPrimaryOutput(), 
        //     deployConfig));
    }
    
    /**
     * Configures appspec.yaml, taskdef.json and imageDetails.json using information coming from the cdk.out (.assets files)
     * @param appName
     * @param targetEnv
     * @param stageNumber
     * @return
     */
    private List<String> configureCodeDeploy(BlueGreenDeployConfig deployConfig ){

        if( deployConfig == null ){
            return Arrays.asList(new String[]{});
        }
        final String stageName =   deployConfig.getStageName();        
        final String account =  deployConfig.getAccount();
        final String region =   deployConfig.getRegion();
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
       
    static class CodeDeployStep extends Step implements ICodePipelineActionFactory{

        FileSet fileSet =   null;
        IRole codeDeployRole    =   null;
        IEcsDeploymentGroup dg  =   null;
        String envType  =   null;

        public CodeDeployStep(String id, String envType, FileSet fileSet, BlueGreenDeployConfig deploymentConfig){
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
        public  CodePipelineActionFactoryResult produceAction(IStage stage, ProduceActionOptions options) {

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