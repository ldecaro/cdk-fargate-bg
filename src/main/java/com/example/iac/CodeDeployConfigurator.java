package com.example.iac;

import java.util.Arrays;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.codedeploy.EcsApplication;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentGroup;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentGroupAttributes;
import software.amazon.awscdk.services.codepipeline.Artifact;
import software.amazon.awscdk.services.codepipeline.IStage;
import software.amazon.awscdk.services.codepipeline.StageOptions;
import software.amazon.awscdk.services.codepipeline.actions.CodeDeployEcsContainerImageInput;
import software.amazon.awscdk.services.codepipeline.actions.CodeDeployEcsDeployAction;
import software.amazon.awscdk.services.iam.Role;
import software.constructs.Construct;

public class CodeDeployConfigurator extends Stack {
    
    public CodeDeployConfigurator(Construct scope, String id, String appName, Pipeline pipeline, StackProps props){
        super(scope, id, props);

        software.amazon.awscdk.services.codepipeline.Pipeline pipe = pipeline.getPipeline();
        Artifact buildArtifact = pipeline.getBuild();
        Artifact sourceArtifact = pipeline.getSource();
        Role deployRole = pipeline.getDeployRole();

        IStage deploy   =   pipe.addStage(StageOptions.builder().stageName("Deploy").build() );
        deploy.addAction(  CodeDeployEcsDeployAction.Builder.create()
                        .actionName("Deploy")
                        .role(deployRole)
                        .appSpecTemplateInput(sourceArtifact)
                        .taskDefinitionTemplateInput(sourceArtifact)
                        .containerImageInputs(Arrays.asList(CodeDeployEcsContainerImageInput.builder()
                                                .input(buildArtifact)
                                                // the properties below are optional
                                                .taskDefinitionPlaceholder("IMAGE1_NAME")
                                                .build()))
                        .deploymentGroup(	EcsDeploymentGroup.fromEcsDeploymentGroupAttributes(this, appName+"-ecsdeploymentgroup", EcsDeploymentGroupAttributes.builder()
                                            .deploymentGroupName( appName )
                                            .application(EcsApplication.fromEcsApplicationName(this, appName+"-ecs-deploy-app", appName))
                                            // pode ser aqui o meu problema depois por não ter associado o deployment config name
                                            .build()))
                        .variablesNamespace("deployment")
                        .build());

    }
}
