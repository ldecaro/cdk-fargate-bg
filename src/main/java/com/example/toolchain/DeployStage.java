package com.example.toolchain;

import java.util.Arrays;
import java.util.List;

import static com.example.Constants.APP_NAME;
import com.example.cdk_fargate_bg.CdkFargateBg;

import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Stage;
import software.amazon.awscdk.StageProps;
import software.amazon.awscdk.pipelines.CodePipelineActionFactoryResult;
import software.amazon.awscdk.pipelines.FileSet;
import software.amazon.awscdk.pipelines.ICodePipelineActionFactory;
import software.amazon.awscdk.pipelines.ProduceActionOptions;
import software.amazon.awscdk.pipelines.ShellStep;
import software.amazon.awscdk.pipelines.Step;
import software.amazon.awscdk.services.codedeploy.IEcsDeploymentGroup;
import software.amazon.awscdk.services.codepipeline.Artifact;
import software.amazon.awscdk.services.codepipeline.IStage;
import software.amazon.awscdk.services.codepipeline.actions.CodeDeployEcsContainerImageInput;
import software.amazon.awscdk.services.codepipeline.actions.CodeDeployEcsDeployAction;
import software.amazon.awscdk.services.iam.IRole;
import software.constructs.Construct;

/**
 * The deployment stage executes 3 main activities: CodeDeploy configuration,
 * component deployment, aplication deployment using CodeDeploy.
 */
public class DeployStage extends Stage {
    
    private Step codeDeployStep = null;
    private Step configCodeDeployStep = null;

    public DeployStage(Construct scope, String id, FileSet cloudAssemblyFileSet, DeployConfig deployConfig, StageProps stageProps){

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

        configCodeDeployStep = ShellStep.Builder.create("ConfigureBlueGreenDeploy")
            .input(cloudAssemblyFileSet)
            .primaryOutputDirectory("codedeploy")    
            .commands(configureCodeDeploy( deployConfig ))
            .build();                    

        this.codeDeployStep = new CodeDeployStep(            
            "codeDeploy"+stageName.toLowerCase(), 
            stageName.toLowerCase(),
            configCodeDeployStep.getPrimaryOutput(), 
            deployConfig);
    }

    public Step getCodeDeployStep(){
        return codeDeployStep;
    }

    public Step getConfigCodeDeployStep(){
        return configCodeDeployStep;
    }

    /**
     * Configures appspec.yaml, taskdef.json and imageDetails.json using information coming from the cdk.out (.assets files)
     * @param appName
     * @param targetEnv
     * @param stageNumber
     * @return
     */
    private List<String> configureCodeDeploy(DeployConfig deployConfig ){

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

    class CodeDeployStep extends Step implements ICodePipelineActionFactory{

        FileSet fileSet =   null;
        IRole codeDeployRole    =   null;
        IEcsDeploymentGroup dg  =   null;
        String envType  =   null;

        public CodeDeployStep(String id, String envType, FileSet fileSet, DeployConfig deploymentConfig){
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
