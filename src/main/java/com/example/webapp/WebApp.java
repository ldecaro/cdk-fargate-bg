package com.example.webapp;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.example.Constants;
import com.example.webapp.compute.infrastructure.Compute;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.codedeploy.EcsApplication;
import software.amazon.awscdk.services.codedeploy.EcsBlueGreenDeploymentConfig;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentGroup;
import software.amazon.awscdk.services.codedeploy.IEcsDeploymentConfig;
import software.amazon.awscdk.services.ecr.assets.DockerImageAsset;
import software.amazon.awscdk.services.ecs.ContainerDefinitionOptions;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.DeploymentController;
import software.amazon.awscdk.services.ecs.DeploymentControllerType;
import software.amazon.awscdk.services.ecs.FargateTaskDefinition;
import software.amazon.awscdk.services.ecs.PortMapping;
import software.amazon.awscdk.services.ecs.Protocol;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateService;
import software.amazon.awscdk.services.elasticloadbalancingv2.AddApplicationActionProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationListener;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationProtocol;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationTargetGroup;
import software.amazon.awscdk.services.elasticloadbalancingv2.BaseApplicationListenerProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.ListenerAction;
import software.amazon.awscdk.services.elasticloadbalancingv2.TargetType;
import software.amazon.awscdk.services.iam.IRole;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.constructs.Construct;

public class WebApp extends Stack {

    private static final String    ECS_TASK_CPU = "1024";
    private static final String    ECS_TASK_MEMORY = "2048";
    private static final Integer   ECS_CONTAINER_MEMORY_RESERVATION = 256;
    private static final Integer   ECS_CONTAINER_MEMORY_LIMIT = 512;
    private static final Integer   ECS_TASK_CONTAINER_PORT = 8080;
    private static final Integer   ECS_TASK_CONTAINER_HOST_PORT = 8080;     

    ApplicationTargetGroup tgGreen   =   null;
    ApplicationListener listenerGreen = null;    


    public WebApp(Construct scope, String id, IEcsDeploymentConfig deploymentConfig, StackProps props){
    
        super(scope, id, props);

        String envType = this.getStackName().substring(this.getStackName().indexOf(Constants.APP_NAME)+Constants.APP_NAME.length());

        //uploading the green application to the ECR
        DockerImageAsset.Builder.create(this, Constants.APP_NAME+"-container")
            .directory("./target")
            .build();

         ApplicationLoadBalancedFargateService albService = ApplicationLoadBalancedFargateService.Builder.create(this, "Service")
            .desiredCount(2)
            .serviceName(Constants.APP_NAME)        
            .deploymentController(DeploymentController.builder().type(DeploymentControllerType.CODE_DEPLOY).build())
            .taskDefinition(createECSTask(new HashMap<String,String>(), Constants.APP_NAME, createTaskRole(), createTaskExecutionRole(envType)))
            .loadBalancerName(Constants.APP_NAME+"Alb"+envType)
            .listenerPort(80)
            .build();

        createGreenListener(albService, envType );        

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
            .deploymentConfig(deploymentConfig)
            .build();

        // In case the component has more resources, 
        // ie. a dynamo table, a lambda implementing a dynamo stream and a monitoring capability
        // they should be added here, as part of the component            

        CfnOutput.Builder.create(this, "VPC")
            .description("Arn of the VPC ")
            .value(albService.getCluster().getVpc().getVpcArn())
            .build();

        CfnOutput.Builder.create(this, "ECSCluster")
            .description("Name of the ECS Cluster ")
            .value(albService.getCluster().getClusterName())
            .build();            

        CfnOutput.Builder.create(this, "TaskRole")
            .description("Role name of the Task being executed ")
            .value(albService.getService().getTaskDefinition().getTaskRole().getRoleName())
            .build();            

        CfnOutput.Builder.create(this, "ExecutionRole")
            .description("Execution Role name of the Task being executed ")
            .value(albService.getService().getTaskDefinition().getExecutionRole().getRoleName())
            .build();              
            
        CfnOutput.Builder.create(this, "ApplicationURL")
            .description("Application is acessible from this url")
            .value("http://"+albService.getLoadBalancer().getLoadBalancerDnsName())
            .build();                             
    }

    public FargateTaskDefinition createECSTask(Map<String, String> env, String serviceName, IRole taskRole, IRole executionRole){

        FargateTaskDefinition taskDef =    null;
        
        taskDef =   FargateTaskDefinition.Builder.create(this, serviceName+"-EcsTaskDef")
            .taskRole(taskRole)
            .executionRole(executionRole)
            .cpu(Integer.parseInt(WebApp.ECS_TASK_CPU))
            .memoryLimitMiB(Integer.parseInt(WebApp.ECS_TASK_MEMORY))
            .family(serviceName)
            .build();    

        taskDef.addContainer(serviceName+"-app", ContainerDefinitionOptions.builder()
            .containerName(serviceName)
            .memoryReservationMiB(ECS_CONTAINER_MEMORY_RESERVATION)
            .memoryLimitMiB(ECS_CONTAINER_MEMORY_LIMIT)
            .image(ContainerImage.fromDockerImageAsset(        
                DockerImageAsset.Builder
                    .create(this, Constants.APP_NAME+"Container")
                    .directory(getPathDockerfile())
                    .build()))
            .essential(Boolean.TRUE)
            .portMappings(Arrays.asList(
                PortMapping.builder()
                    .containerPort(WebApp.ECS_TASK_CONTAINER_PORT)
                    .hostPort(WebApp.ECS_TASK_CONTAINER_HOST_PORT)
                    .protocol(Protocol.TCP)
                .build()))          
            .environment(env)
            .build());            

        return taskDef;
    }   
    
    private String getPathDockerfile(){

        String path = "./target/classes/";
        path += this.getClass().getName().substring(0, this.getClass().getName().lastIndexOf(".")).replace(".", "/");
        path += "/compute/runtime-bootstrap";

        return path;
    }    

    Role createTaskRole(){

        return Role.Builder.create(this, Constants.APP_NAME+"EcsTaskRole")
            .assumedBy(ServicePrincipal.Builder.create("ecs-tasks.amazonaws.com").build())
            .managedPolicies(Arrays.asList(
                ManagedPolicy.fromAwsManagedPolicyName("CloudWatchFullAccess"), 
                ManagedPolicy.fromAwsManagedPolicyName("AWSXRayDaemonWriteAccess"), 
                ManagedPolicy.fromAwsManagedPolicyName("AWSAppMeshEnvoyAccess"), 
                ManagedPolicy.fromAwsManagedPolicyName("CloudWatchAgentServerPolicy")))
            .build();        
    }

    Role createTaskExecutionRole(final String strEnvType){
        
        return Role.Builder.create(this, Constants.APP_NAME+"EcsExecutionRole")
            .roleName(Constants.APP_NAME+"-"+strEnvType)
            .assumedBy(ServicePrincipal.Builder.create("ecs-tasks.amazonaws.com").build())
            .managedPolicies(Arrays.asList(
                ManagedPolicy.fromManagedPolicyArn(
                    this, 
                    "ecsTaskExecutionManagedPolicy", 
                    "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"),
                ManagedPolicy.fromAwsManagedPolicyName("CloudWatchAgentServerPolicy")
            )).build();        
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
    
    public void createGreenListener(ApplicationLoadBalancedFargateService albService, String envType){

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
            
        this.tgGreen = tgGreen;
        this.listenerGreen = listenerGreen;
    }
}