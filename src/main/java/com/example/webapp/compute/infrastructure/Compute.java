package com.example.webapp.compute.infrastructure;

import static com.example.Constants.APP_NAME;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.example.Constants;
import com.example.webapp.codedeploy.infrastructure.BlueGreenDeploy;
import com.example.webapp.network.infrastructure.Network;

import software.amazon.awscdk.services.codedeploy.IEcsDeploymentConfig;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ecr.assets.DockerImageAsset;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ContainerDefinitionOptions;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.DeploymentController;
import software.amazon.awscdk.services.ecs.DeploymentControllerType;
import software.amazon.awscdk.services.ecs.FargateService;
import software.amazon.awscdk.services.ecs.FargateTaskDefinition;
import software.amazon.awscdk.services.ecs.PortMapping;
import software.amazon.awscdk.services.ecs.Protocol;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateService;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationLoadBalancer;
import software.amazon.awscdk.services.iam.IRole;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.constructs.Construct;

public class Compute extends Construct{
    
    private Cluster cluster = null;
    private ApplicationLoadBalancedFargateService service  = null;
    private IRole taskRole  = null;
    private IRole taskExecutionRole = null;
    private SecurityGroup sg = null;

    private static final String    ECS_TASK_CPU = "1024";
    private static final String    ECS_TASK_MEMORY = "2048";
    private static final Integer   ECS_CONTAINER_MEMORY_RESERVATION = 256;
    private static final Integer   ECS_CONTAINER_MEMORY_LIMIT = 512;
    private static final Integer   ECS_TASK_CONTAINER_PORT = 8080;
    private static final Integer   ECS_TASK_CONTAINER_HOST_PORT = 8080;      

    public Compute(Construct scope, final String id, final IEcsDeploymentConfig deployConfig, final String strEnvType, Network ecsNetwork){

        super(scope,id);    

        //uploading the green application to the ECR
        DockerImageAsset.Builder.create(this, APP_NAME+"-container")
            .directory("./target")
            .build();        

        Cluster cluster =   Cluster.Builder.create(this, APP_NAME+"ECSCluster")
            .vpc(ecsNetwork.getVpc())
            .build();

        SecurityGroup sg    =   SecurityGroup.Builder.create(this, APP_NAME+"SgALB")
            .vpc(cluster.getVpc())
            .allowAllOutbound(Boolean.TRUE)
            .build();            
        
        sg.addIngressRule(Peer.anyIpv4(), Port.allTcp());
        sg.addIngressRule(Peer.anyIpv4(), Port.allUdp());         

        this.sg =   sg;

        Role taskRole   =   createTaskRole();
        Role taskExecutionRole  =   createExecutionRole(strEnvType);

        ApplicationLoadBalancedFargateService service = createFargateService(
            APP_NAME, 
            cluster, 
            taskRole,
            taskExecutionRole, 
            strEnvType);

        new BlueGreenDeploy(this, "CodeDeployBlueGreen", deployConfig, strEnvType, service);           

        this.cluster = cluster;
        this.service = service;
        this.taskRole = taskRole;
        this.taskExecutionRole = taskExecutionRole;
    }

    ApplicationLoadBalancedFargateService createFargateService(String appName, Cluster cluster, Role taskRole, Role executionRole, String strEnvType ){

        ApplicationLoadBalancedFargateService service = ApplicationLoadBalancedFargateService.Builder.create(this, "Service")
            .desiredCount(2)
            .cluster(cluster)
            .serviceName(appName)        
            .deploymentController(DeploymentController.builder().type(DeploymentControllerType.CODE_DEPLOY).build())
            .securityGroups(Arrays.asList(this.sg))
            .taskDefinition(createECSTask(new HashMap<String,String>(), appName, taskRole, executionRole))
            .loadBalancerName(Constants.APP_NAME+"Alb"+strEnvType)
            .listenerPort(80)
            .build();
        
        return service;
    }     
    
    private FargateTaskDefinition createECSTask(Map<String, String> env, String serviceName, Role taskRole, Role executionRole){

        FargateTaskDefinition taskDef =    null;
        
        taskDef =   FargateTaskDefinition.Builder.create(this, serviceName+"-EcsTaskDef")
            .taskRole(taskRole)
            .executionRole(executionRole)
            .cpu(Integer.parseInt(Compute.ECS_TASK_CPU))
            .memoryLimitMiB(Integer.parseInt(Compute.ECS_TASK_MEMORY))
            .family(serviceName)
            .build();    

        taskDef.addContainer(serviceName+"-app", ContainerDefinitionOptions.builder()
            .containerName(serviceName)
            .memoryReservationMiB(ECS_CONTAINER_MEMORY_RESERVATION)
            .memoryLimitMiB(ECS_CONTAINER_MEMORY_LIMIT)
            .image(ContainerImage.fromDockerImageAsset(        
                DockerImageAsset.Builder
                    .create(this, APP_NAME+"Container")
                    .directory(getPathDockerfile())
                    .build()))
            .essential(Boolean.TRUE)
            .portMappings(Arrays.asList(
                PortMapping.builder()
                    .containerPort(Compute.ECS_TASK_CONTAINER_PORT)
                    .hostPort(Compute.ECS_TASK_CONTAINER_HOST_PORT)
                    .protocol(Protocol.TCP)
                .build()))          
            .environment(env)
            .build());            

        return taskDef;
    }   
    
    private String getPathDockerfile(){

        String path = "./target/classes/";
        path += this.getClass().getName().substring(0, this.getClass().getName().lastIndexOf(".")).replace(".", "/");
        path += "/../runtime-bootstrap";

        return path;
    }
    
    public Cluster getCluster(){
        return this.cluster;
    }

    public FargateService getFargateService(){
        return this.service.getService();
    }

    public IRole getTaskRole(){
        return taskRole;
    }

    public IRole getTaskExecutionRole(){
        return taskExecutionRole;
    }

    public ApplicationLoadBalancer getALB(){
        return this.service.getLoadBalancer();
    }

    Role createTaskRole(){

        return Role.Builder.create(this, APP_NAME+"EcsTaskRole")
            .assumedBy(ServicePrincipal.Builder.create("ecs-tasks.amazonaws.com").build())
            .managedPolicies(Arrays.asList(
                ManagedPolicy.fromAwsManagedPolicyName("CloudWatchFullAccess"), 
                ManagedPolicy.fromAwsManagedPolicyName("AWSXRayDaemonWriteAccess"), 
                ManagedPolicy.fromAwsManagedPolicyName("AWSAppMeshEnvoyAccess"), 
                ManagedPolicy.fromAwsManagedPolicyName("CloudWatchAgentServerPolicy")))
            .build();        
    }

    Role createExecutionRole(final String strEnvType){
        
        return Role.Builder.create(this, APP_NAME+"EcsExecutionRole")
            .roleName(APP_NAME+"-"+strEnvType)
            .assumedBy(ServicePrincipal.Builder.create("ecs-tasks.amazonaws.com").build())
            .managedPolicies(Arrays.asList(
                ManagedPolicy.fromManagedPolicyArn(
                    this, 
                    "ecsTaskExecutionManagedPolicy", 
                    "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"),
                ManagedPolicy.fromAwsManagedPolicyName("CloudWatchAgentServerPolicy")
            )).build();        
    }    
}