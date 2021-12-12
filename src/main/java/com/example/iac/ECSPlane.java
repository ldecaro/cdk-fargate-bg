package com.example.iac;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.assets.DockerImageAsset;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.Compatibility;
import software.amazon.awscdk.services.ecs.ContainerDefinitionOptions;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.DeploymentController;
import software.amazon.awscdk.services.ecs.DeploymentControllerType;
import software.amazon.awscdk.services.ecs.EcsTarget;
import software.amazon.awscdk.services.ecs.FargateService;
import software.amazon.awscdk.services.ecs.ListenerConfig;
import software.amazon.awscdk.services.ecs.NetworkMode;
import software.amazon.awscdk.services.ecs.PortMapping;
import software.amazon.awscdk.services.ecs.Protocol;
import software.amazon.awscdk.services.ecs.TaskDefinition;
import software.amazon.awscdk.services.elasticloadbalancingv2.AddApplicationTargetGroupsProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.AddApplicationTargetsProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationListener;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationLoadBalancer;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationProtocol;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationTargetGroup;
import software.amazon.awscdk.services.elasticloadbalancingv2.BaseApplicationListenerProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.TargetType;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.constructs.Construct;

public class ECSPlane extends Stack {
    
    public ECSPlane(Construct scope, String id, String appName, StackProps props){
        super(scope, id, props);

        DockerImageAsset dockerAsset    =   createDockerAsset();

        Vpc vpc = Vpc.Builder.create(this, appName+"-vpc") 
            .cidr("10.0.50.0/24")
            .maxAzs(2)
            .enableDnsHostnames(Boolean.TRUE)
            .enableDnsSupport(Boolean.TRUE)
            .build();
        
        SecurityGroup sg    =   SecurityGroup.Builder.create(this, appName+"-sg").vpc(vpc).allowAllOutbound(Boolean.TRUE).build();
        sg.addIngressRule(Peer.anyIpv4(), Port.allTcp());
        sg.addIngressRule(Peer.anyIpv4(), Port.allUdp());
        
        Cluster cluster =   Cluster.Builder.create(this, appName+"-cluster")
        .vpc(vpc)
        .clusterName(appName)
        .containerInsights(Boolean.TRUE)
        .build();

        Role taskRole = Role.Builder.create(this, appName+"-ecsTaskRole")
        .assumedBy(ServicePrincipal.Builder.create("ecs-tasks.amazonaws.com").build())
        .managedPolicies(Arrays.asList(
            ManagedPolicy.fromAwsManagedPolicyName("CloudWatchFullAccess"), 
            ManagedPolicy.fromAwsManagedPolicyName("AWSXRayDaemonWriteAccess"), 
            ManagedPolicy.fromAwsManagedPolicyName("AWSAppMeshEnvoyAccess"), 
            ManagedPolicy.fromAwsManagedPolicyName("CloudWatchAgentServerPolicy")))
        .build();

        Role executionRole = Role.Builder.create(this, appName+"-ecsExecutionRole")
        .assumedBy(ServicePrincipal.Builder.create("ecs-tasks.amazonaws.com").build())
        .managedPolicies(Arrays.asList(
            ManagedPolicy.fromManagedPolicyArn(this, "ecsTaskExecutionManagedPolicy", "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"),
            ManagedPolicy.fromAwsManagedPolicyName("CloudWatchAgentServerPolicy")
        )).build();
        
        createFargateService(appName, cluster, dockerAsset, appName, taskRole, executionRole);
    }


    public DockerImageAsset createDockerAsset(){

        //Create the 3 containers, one for each project: Gateway (Envoy only), Morning and Afternoon Services.  
        DockerImageAsset dockerAsset = DockerImageAsset.Builder
            .create(this, "hello-world/v1")
            .directory("./")
            .build();
        return dockerAsset;
    }

    private FargateService createFargateService(String appName, Cluster cluster, DockerImageAsset appContainer, String serviceName, Role taskRole, Role executionRole ){


        SecurityGroup sg    =   SecurityGroup.Builder.create(this, serviceName+"-fargatesvc-sg").vpc(cluster.getVpc()).allowAllOutbound(Boolean.TRUE).build();
        sg.addIngressRule(Peer.anyIpv4(), Port.allTcp());
        sg.addIngressRule(Peer.anyIpv4(), Port.allUdp());

        // String rand = Util.randomString(4);
        FargateService service  =   FargateService.Builder.create(this, serviceName+"-fargateSvc")
            .desiredCount(1)
            .cluster( cluster )
            .serviceName(serviceName+"-"+Util.randomString(4))
            .deploymentController(DeploymentController.builder().type(DeploymentControllerType.CODE_DEPLOY).build())
            // .circuitBreaker(DeploymentCircuitBreaker.builder().rollback(Boolean.TRUE).build())
            .securityGroups(Arrays.asList(sg))
            .taskDefinition(createECSTask(appName, appContainer, new HashMap<String,String>(), serviceName, taskRole, executionRole))
             .build();

        ApplicationLoadBalancer lb = ApplicationLoadBalancer.Builder.create(this, appName+"-LB").loadBalancerName(appName+"-alb").vpc(cluster.getVpc()).internetFacing(true).build();
        ApplicationListener listener = lb.addListener("Listener", BaseApplicationListenerProps.builder().port(80).build());        

        service.registerLoadBalancerTargets(EcsTarget.builder()
                .containerName(serviceName)
                .containerPort(8080)
                .newTargetGroupId("ECS")
                .listener(ListenerConfig.applicationListener(listener, AddApplicationTargetsProps.builder()
                        .protocol(ApplicationProtocol.HTTP)
                        .targetGroupName(appName+"-Green")
                        .build()))
                .build());

        AddApplicationTargetGroupsProps.builder()
                                    .targetGroups(Arrays.asList(ApplicationTargetGroup.Builder.create(this, appName+"_blue-tg")
                                        .protocol(ApplicationProtocol.HTTP)
                                        .targetGroupName(appName+"-Blue")
                                        .targetType(TargetType.IP)
                                        .vpc(cluster.getVpc())
                                        .build()));

        return service;
    }    

    private TaskDefinition createECSTask(String appName, DockerImageAsset appContainer, Map<String, String> env, String serviceName, Role taskRole, Role executionRole){

        TaskDefinition taskDef =    null;
        
        taskDef =   TaskDefinition.Builder.create(this, serviceName+"-ecsTaskDef")
        .taskRole(taskRole)
        .executionRole(executionRole)
        .networkMode(NetworkMode.AWS_VPC)
        .cpu("1024")
        .memoryMiB("2048")
        .family(serviceName)
        .compatibility(Compatibility.FARGATE)
        .build();    

        if( appContainer != null ){
            //adding application container
            taskDef.addContainer( serviceName+"-app", ContainerDefinitionOptions.builder()
            .containerName(serviceName)
            .memoryReservationMiB(256)
            .memoryLimitMiB(512)
            .image(ContainerImage.fromDockerImageAsset(appContainer))
            .essential(Boolean.TRUE)
            //.healthCheck(software.amazon.awscdk.services.ecs.HealthCheck.builder().command(Arrays.asList("CMD-SHELL", "curl -s http://localhost:8080/Luiz | grep name")).interval(Duration.seconds(5)).timeout(Duration.seconds(2)).retries(3).startPeriod(Duration.seconds(10)).build())
            .portMappings(Arrays.asList(
                PortMapping.builder().containerPort(8080).hostPort(8080).protocol(Protocol.TCP).build()))          
            .environment(env)
            .build());
        }
        return taskDef;
    }        
}
