package com.example.webapp.codedeploy.infrastructure;

import java.util.Arrays;

import com.example.Constants;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.services.codedeploy.EcsApplication;
import software.amazon.awscdk.services.codedeploy.EcsBlueGreenDeploymentConfig;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentGroup;
import software.amazon.awscdk.services.codedeploy.IEcsDeploymentConfig;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateService;
import software.amazon.awscdk.services.elasticloadbalancingv2.AddApplicationActionProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationListener;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationProtocol;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationTargetGroup;
import software.amazon.awscdk.services.elasticloadbalancingv2.BaseApplicationListenerProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.ListenerAction;
import software.amazon.awscdk.services.elasticloadbalancingv2.TargetType;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.constructs.Construct;

public class BlueGreenDeploy extends Construct {
    

    public BlueGreenDeploy(Construct scope, String id, IEcsDeploymentConfig deployConfig, String envType, ApplicationLoadBalancedFargateService albService){
        
        super(scope, id);

        //create the green listener and target group
        String tgGreenName = Constants.APP_NAME+"-"+envType+"-Green";
        tgGreenName = tgGreenName.length()>32 ? tgGreenName.substring(tgGreenName.length()-32) : tgGreenName;

        ApplicationTargetGroup tgGreen   =   ApplicationTargetGroup.Builder.create(this, Constants.APP_NAME+"GreenTg")
            .protocol(ApplicationProtocol.HTTP)
            .targetGroupName(tgGreenName)
            .targetType(TargetType.IP)
            .vpc(albService.getCluster().getVpc())
            .build();

        ApplicationListener listenerGreen = albService.getLoadBalancer().addListener("BgListenerGreen", BaseApplicationListenerProps.builder()
            .port(8080)
            .defaultTargetGroups(Arrays.asList(tgGreen))
            .protocol(ApplicationProtocol.HTTP)
            .build());

        listenerGreen.addAction(Constants.APP_NAME+"ListenerGreenAction", AddApplicationActionProps.builder()
            .action(ListenerAction.forward(Arrays.asList( tgGreen )))
            .build());          

        //configure AWS CodeDeploy Application and DeploymentGroup
        EcsApplication app = EcsApplication.Builder.create(this, "BlueGreenApplication")
            .applicationName(Constants.APP_NAME+"-"+envType)
            .build();

        EcsDeploymentGroup.Builder.create(this, "BlueGreenDeploymentGroup")
            .deploymentGroupName(Constants.APP_NAME+"-"+envType)
            .application(app)
            .service(albService.getService())
            .role(createCodeDeployExecutionRole())
            .blueGreenDeploymentConfig(EcsBlueGreenDeploymentConfig.builder()
                    .blueTargetGroup(albService.getTargetGroup())
                    .greenTargetGroup(tgGreen)
                    .listener(albService.getListener())
                    .testListener(listenerGreen)
                    .terminationWaitTime(Duration.minutes(15))
                    .build())
            .deploymentConfig(deployConfig)
            .build();
    }

    private Role createCodeDeployExecutionRole(){

        return Role.Builder.create(this, Constants.APP_NAME+"CodeDeployExecRole")
            .assumedBy(ServicePrincipal.Builder.create("codedeploy.amazonaws.com").build())
            .description("CodeBuild Execution Role for "+Constants.APP_NAME)
            .path("/")
            .managedPolicies(Arrays.asList(
                ManagedPolicy.fromAwsManagedPolicyName("AWSCodeBuildDeveloperAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("AmazonEC2ContainerRegistryFullAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("AmazonECS_FullAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("CloudWatchLogsFullAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("AWSCodeDeployRoleForECS"),
                ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole")
            ))
            .build();
    }      
}
