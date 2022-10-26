package com.example.toolchain;

import java.util.Arrays;

import software.amazon.awscdk.pipelines.CodePipelineActionFactoryResult;
import software.amazon.awscdk.pipelines.FileSet;
import software.amazon.awscdk.pipelines.ICodePipelineActionFactory;
import software.amazon.awscdk.pipelines.ProduceActionOptions;
import software.amazon.awscdk.pipelines.Step;
import software.amazon.awscdk.services.codedeploy.IEcsDeploymentGroup;
import software.amazon.awscdk.services.codepipeline.Artifact;
import software.amazon.awscdk.services.codepipeline.IStage;
import software.amazon.awscdk.services.codepipeline.actions.CodeDeployEcsContainerImageInput;
import software.amazon.awscdk.services.codepipeline.actions.CodeDeployEcsDeployAction;
import software.amazon.awscdk.services.iam.IRole;
import software.amazon.awscdk.services.iam.Role;

class NewCodeDeployStep extends Step implements ICodePipelineActionFactory{

    FileSet fileSet =   null;
    IRole codeDeployRole    =   null;
    IEcsDeploymentGroup dg  =   null;
    String envType  =   null;

    public NewCodeDeployStep(String id, String envType, FileSet fileSet, IRole deployRole, IEcsDeploymentGroup dg){
        super(id);
        this.fileSet    =   fileSet;
        this.codeDeployRole =   deployRole;
        //this.dg    =   deploymentConfig.getEcsDeploymentGroup();
        this.dg = dg;
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