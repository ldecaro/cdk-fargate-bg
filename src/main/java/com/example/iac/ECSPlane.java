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
import software.amazon.awscdk.services.ecs.IEcsLoadBalancerTarget;
import software.amazon.awscdk.services.ecs.ListenerConfig;
import software.amazon.awscdk.services.ecs.LoadBalancerTargetOptions;
import software.amazon.awscdk.services.ecs.NetworkMode;
import software.amazon.awscdk.services.ecs.PortMapping;
import software.amazon.awscdk.services.ecs.Protocol;
import software.amazon.awscdk.services.ecs.TaskDefinition;
import software.amazon.awscdk.services.elasticloadbalancingv2.AddApplicationActionProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.AddApplicationTargetsProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationListener;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationLoadBalancer;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationProtocol;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationTargetGroup;
import software.amazon.awscdk.services.elasticloadbalancingv2.BaseApplicationListenerProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.ListenerAction;
import software.amazon.awscdk.services.elasticloadbalancingv2.TargetGroupAttributes;
import software.amazon.awscdk.services.elasticloadbalancingv2.TargetType;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.constructs.Construct;

public class ECSPlane extends Stack {

    private String listenerBlueArn;
    private String listenerGreenArn;
    private String tgBlueName;
    private String tgGreenName;
    private String ecsTaskExecutionRoleName;
    
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
        .roleName(appName+"-TaskRole")
        .assumedBy(ServicePrincipal.Builder.create("ecs-tasks.amazonaws.com").build())
        .managedPolicies(Arrays.asList(
            ManagedPolicy.fromAwsManagedPolicyName("CloudWatchFullAccess"), 
            ManagedPolicy.fromAwsManagedPolicyName("AWSXRayDaemonWriteAccess"), 
            ManagedPolicy.fromAwsManagedPolicyName("AWSAppMeshEnvoyAccess"), 
            ManagedPolicy.fromAwsManagedPolicyName("CloudWatchAgentServerPolicy")))
        .build();

        String roleName = appName+"-TaskExecutionRole";
        Role executionRole = Role.Builder.create(this, appName+"-ecsExecutionRole")
        .roleName(roleName)
        .assumedBy(ServicePrincipal.Builder.create("ecs-tasks.amazonaws.com").build())
        .managedPolicies(Arrays.asList(
            ManagedPolicy.fromManagedPolicyArn(this, "ecsTaskExecutionManagedPolicy", "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"),
            ManagedPolicy.fromAwsManagedPolicyName("CloudWatchAgentServerPolicy")
        )).build();
        this.ecsTaskExecutionRoleName   =   roleName;

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

        ApplicationLoadBalancer lb = ApplicationLoadBalancer.Builder.create(this, appName+"-LB").loadBalancerName(appName+"-alb").vpc(cluster.getVpc()).internetFacing(true).build();
        // ApplicationTargetGroup tgBlue   =   ApplicationTargetGroup.Builder.create(this, appName+"-blue-tg")
        //                                                     .protocol(ApplicationProtocol.HTTP)
        //                                                     .targetGroupName(appName+"-Blue")
        //                                                     .targetType(TargetType.IP)
        //                                                     .vpc(cluster.getVpc())
        //                                                     .build();
        // ApplicationListener listener = lb.addListener("Listener-Blue", BaseApplicationListenerProps.builder().port(80).protocol(ApplicationProtocol.HTTP).defaultTargetGroups(Arrays.asList(tgBlue)).build());   
        ApplicationListener listener = lb.addListener("Listener-Blue", BaseApplicationListenerProps.builder().port(80).protocol(ApplicationProtocol.HTTP).build());
        // listener.addAction(appName+"-listener-blue-action", AddApplicationActionProps.builder().action(ListenerAction.forward(Arrays.asList(ApplicationTargetGroup.fromTargetGroupAttributes(this, appName+"tg-bg-blue", TargetGroupAttributes.builder().targetGroupArn(tgBlue.getTargetGroupArn()).build() )))).build());

        SecurityGroup sg    =   SecurityGroup.Builder.create(this, serviceName+"-fargatesvc-sg").vpc(cluster.getVpc()).allowAllOutbound(Boolean.TRUE).build();
        sg.addIngressRule(Peer.anyIpv4(), Port.allTcp());
        sg.addIngressRule(Peer.anyIpv4(), Port.allUdp());           

        ApplicationTargetGroup tgGreen   =   ApplicationTargetGroup.Builder.create(this, appName+"-green-tg")
                .protocol(ApplicationProtocol.HTTP)
                .targetGroupName(appName+"-Green")
                .targetType(TargetType.IP)
                .vpc(cluster.getVpc())
                .build();

        ApplicationListener listenerGreen = lb.addListener("Listener-Green", BaseApplicationListenerProps.builder().port(8080).defaultTargetGroups(Arrays.asList(tgGreen)).protocol(ApplicationProtocol.HTTP).build());
        listenerGreen.addAction(appName+"-listener-green-action", AddApplicationActionProps.builder().action(ListenerAction.forward(Arrays.asList( tgGreen ))).build());

        // String rand = Util.randomString(4);
        FargateService service  =   FargateService.Builder.create(this, serviceName+"-fargateSvc")
            .desiredCount(1)
            .cluster( cluster )
            // .serviceName(serviceName+"-"+Util.randomString(4))
            .serviceName(serviceName)
            .deploymentController(DeploymentController.builder().type(DeploymentControllerType.CODE_DEPLOY).build())
            // .circuitBreaker(DeploymentCircuitBreaker.builder().rollback(Boolean.TRUE).build())
            .securityGroups(Arrays.asList(sg))
            .taskDefinition(createECSTask(appName, appContainer, new HashMap<String,String>(), serviceName, taskRole, executionRole))
            .build();
                    
        // service.registerLoadBalancerTargets(EcsTarget.builder()
        //         .containerName(serviceName)
        //         .containerPort(8080)
        //         .newTargetGroupId("ECS")
        //         .listener(ListenerConfig.applicationListener(listener))
        //         .build());     

        String tgBlueName = appName+"-Blue";    
        listener.addTargets("test-target", AddApplicationTargetsProps.builder().targetGroupName(tgBlueName).protocol(ApplicationProtocol.HTTP).port(8080).targets(Arrays.asList(service)).build() );

        this.listenerBlueArn = listener.getListenerArn();
        this.listenerGreenArn = listenerGreen.getListenerArn();
    
        // this.tgBlueName = tgBlue.getTargetGroupName();
        this.tgBlueName = tgBlueName;
        this.tgGreenName= tgGreen.getTargetGroupName();        

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


    public String getListenerBlueArn() {
        return listenerBlueArn;
    }


    public String getListenerGreenArn() {
        return listenerGreenArn;
    }


    public String getTgBlueName() {
        return tgBlueName;
    }


    public String getTgGreenName() {
        return tgGreenName;
    }
    
    public String getEcsTaskExecutionRoleName(){
        return this.ecsTaskExecutionRoleName;
    }
}
