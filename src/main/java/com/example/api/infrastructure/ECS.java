package com.example.api.infrastructure;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import software.amazon.awscdk.CustomResource;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.customresources.Provider;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ecr.assets.DockerImageAsset;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.Compatibility;
import software.amazon.awscdk.services.ecs.ContainerDefinitionOptions;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.DeploymentController;
import software.amazon.awscdk.services.ecs.DeploymentControllerType;
import software.amazon.awscdk.services.ecs.FargateService;
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
import software.amazon.awscdk.services.elasticloadbalancingv2.TargetType;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.IRole;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.PolicyDocument;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.SingletonFunction;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.constructs.Construct;

public class ECS extends Construct{
    
    private Cluster cluster =   null;
    private FargateService service  =   null;
    private IRole taskRole  =   null;
    private IRole taskExecutionRole = null;
    private ApplicationLoadBalancer alb = null;

    private static final String    ECS_TASK_CPU = "1024";
    private static final String    ECS_TASK_MEMORY = "2048";
    private static final Integer   ECS_CONTAINER_MEMORY_RESERVATION = 256;
    private static final Integer   ECS_CONTAINER_MEMORY_LIMIT = 512;
    private static final Integer   ECS_TASK_CONTAINER_PORT = 8080;
    private static final Integer   ECS_TASK_CONTAINER_HOST_PORT = 8080;      

    private String listenerBlueArn  = null;
    private String listenerGreenArn = null;
    private String tgBlueName       = null;
    private String tgGreenName      = null;    
    private SecurityGroup sg        = null;    

    public ECS(Construct scope, final String id, final String appName, final String deploymentType, final String strEnvType, Network ecsNetwork, StackProps props ){

        super(scope,id);    

        Cluster cluster =   Cluster.Builder.create(this, appName+"-cluster")
            .vpc(ecsNetwork.getVpc())
            .build();

        ApplicationLoadBalancer alb     =   createALB( 
            appName, 
            appName, 
            cluster, 
            strEnvType);

        Role taskRole   =   createTaskRole(appName);
        Role taskExecutionRole  =   createExecutionRole(appName, strEnvType);

        FargateService service = createFargateService(
            appName, 
            cluster, 
            alb, 
            taskRole,
            taskExecutionRole, 
            strEnvType);

        createCustomResource(
            appName+"-"+strEnvType, 
            cluster.getClusterName(), 
            service.getServiceName(), 
            deploymentType, 
            props);              

        this.cluster = cluster;
        this.service = service;
        this.taskRole = taskRole;
        this.taskExecutionRole = taskExecutionRole;
        this.alb = alb;
    }

    public Cluster getCluster(){
        return this.cluster;
    }

    public FargateService getFargateService(){
        return this.service;
    }

    public IRole getTaskRole(){
        return taskRole;
    }

    public IRole getTaskExecutionRole(){
        return taskExecutionRole;
    }

    public ApplicationLoadBalancer getALB(){
        return alb;
    }

    Role createTaskRole(final String appName){

        return Role.Builder.create(this, appName+"-ecsTaskRole")
            .assumedBy(ServicePrincipal.Builder.create("ecs-tasks.amazonaws.com").build())
            .managedPolicies(Arrays.asList(
                ManagedPolicy.fromAwsManagedPolicyName("CloudWatchFullAccess"), 
                ManagedPolicy.fromAwsManagedPolicyName("AWSXRayDaemonWriteAccess"), 
                ManagedPolicy.fromAwsManagedPolicyName("AWSAppMeshEnvoyAccess"), 
                ManagedPolicy.fromAwsManagedPolicyName("CloudWatchAgentServerPolicy")))
            .build();        
    }

    Role createExecutionRole(final String appName, final String strEnvType){
        
        return Role.Builder.create(this, appName+"-ecsExecutionRole")
            .roleName(appName+"-"+strEnvType)
            .assumedBy(ServicePrincipal.Builder.create("ecs-tasks.amazonaws.com").build())
            .managedPolicies(Arrays.asList(
                ManagedPolicy.fromManagedPolicyArn(
                    this, 
                    "ecsTaskExecutionManagedPolicy", 
                    "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"),
                ManagedPolicy.fromAwsManagedPolicyName("CloudWatchAgentServerPolicy")
            )).build();        
    }

    FargateService createFargateService(String appName, Cluster cluster, ApplicationLoadBalancer lb, Role taskRole, Role executionRole, String strEnvType ){

        FargateService service  =   FargateService.Builder.create(this, appName+"-fargateSvc")
            .desiredCount(1)
            .cluster(cluster)
            .serviceName(appName)
            .deploymentController(DeploymentController.builder().type(DeploymentControllerType.CODE_DEPLOY).build())
            .securityGroups(Arrays.asList(this.sg))
            .taskDefinition(createECSTask(appName, new HashMap<String,String>(), appName, taskRole, executionRole))
            .build();
  
        ApplicationListener listener = lb.getListeners().get(0);

        String tgBlueName = appName+"-"+strEnvType+"-Blue";
        tgBlueName = tgBlueName.length()>32 ? tgBlueName.substring(tgBlueName.length()-32) : tgBlueName;
        listener.addTargets(
            appName+"blue-tg", 
            AddApplicationTargetsProps.builder()
                .targetGroupName(tgBlueName)
                .protocol(ApplicationProtocol.HTTP)
                .port(8080)
                .targets(Arrays.asList(service))
                .build());
        this.tgBlueName = tgBlueName;
        
        return service;
    }     
    
    private TaskDefinition createECSTask(String appName, Map<String, String> env, String serviceName, Role taskRole, Role executionRole){

        TaskDefinition taskDef =    null;
        
        taskDef =   TaskDefinition.Builder.create(this, serviceName+"-ecsTaskDef")
            .taskRole(taskRole)
            .executionRole(executionRole)
            .networkMode(NetworkMode.AWS_VPC)
            .cpu(ECS.ECS_TASK_CPU)
            .memoryMiB(ECS.ECS_TASK_MEMORY)
            .family(serviceName)
            .compatibility(Compatibility.FARGATE)
            .build();    

        taskDef.addContainer(serviceName+"-app", ContainerDefinitionOptions.builder()
            .containerName(serviceName)
            .memoryReservationMiB(ECS_CONTAINER_MEMORY_RESERVATION)
            .memoryLimitMiB(ECS_CONTAINER_MEMORY_LIMIT)
            .image(ContainerImage.fromDockerImageAsset(        
                DockerImageAsset.Builder
                    .create(this, appName+"-container")
                    .directory(getPathDockerfile())
                    .build()))
            .essential(Boolean.TRUE)
            .portMappings(Arrays.asList(
                PortMapping.builder()
                    .containerPort(ECS.ECS_TASK_CONTAINER_PORT)
                    .hostPort(ECS.ECS_TASK_CONTAINER_HOST_PORT)
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

    ApplicationLoadBalancer createALB(final String appName, final String serviceName, final Cluster cluster, final String strEnvType){
        
        ApplicationLoadBalancer alb = ApplicationLoadBalancer.Builder.create(this, appName+"-LB")
            .loadBalancerName(appName+"-alb-"+strEnvType).vpc(cluster.getVpc()).internetFacing(true)
            .build();

        ApplicationListener listener = alb.addListener("Listener-Blue", BaseApplicationListenerProps.builder()
            .port(80)
            .protocol(ApplicationProtocol.HTTP)
            .build());

        SecurityGroup sg    =   SecurityGroup.Builder.create(this, serviceName+"-fargatesvc-sg")
            .vpc(cluster.getVpc())
            .allowAllOutbound(Boolean.TRUE)
            .build();

        sg.addIngressRule(Peer.anyIpv4(), Port.allTcp());
        sg.addIngressRule(Peer.anyIpv4(), Port.allUdp());           

        String tgGreenName = appName+"-"+strEnvType+"-Green";
        tgGreenName = tgGreenName.length()>32 ? tgGreenName.substring(tgGreenName.length()-32) : tgGreenName;

        ApplicationTargetGroup tgGreen   =   ApplicationTargetGroup.Builder.create(this, appName+"-green-tg")
            .protocol(ApplicationProtocol.HTTP)
            .targetGroupName(tgGreenName)
            .targetType(TargetType.IP)
            .vpc(cluster.getVpc())
            .build();

        ApplicationListener listenerGreen = alb.addListener("Listener-Green", BaseApplicationListenerProps.builder()
            .port(8080)
            .defaultTargetGroups(Arrays.asList(tgGreen))
            .protocol(ApplicationProtocol.HTTP)
            .build());

        listenerGreen.addAction(appName+"-listener-green-action", AddApplicationActionProps.builder()
            .action(ListenerAction.forward(Arrays.asList( tgGreen )))
            .build());

        this.listenerBlueArn = listener.getListenerArn();
        this.listenerGreenArn = listenerGreen.getListenerArn();
        this.tgGreenName= tgGreen.getTargetGroupName();
        this.sg = sg;
        return alb;
    }

    SingletonFunction createCustomResource(String appName, String clusterName, String serviceName, String deploymentConfigName, StackProps props){

        Role deployRole         =   createCodeDeployExecutionRole(appName, props);
        Role customLambdaRole   =   createCustomLambdaRole(appName, props, deployRole);

        // create CustomLambda to execute CLI command and create the Deployment Group     
        final Map<String,String> lambdaEnv	=	new HashMap<>();
        lambdaEnv.put("appName", appName);
        lambdaEnv.put("accountNumber", props.getEnv().getAccount());
        lambdaEnv.put("roleName", deployRole.getRoleArn());
        lambdaEnv.put("greenListenerArn", listenerGreenArn == null ? "" : listenerGreenArn);
        lambdaEnv.put("blueListenerArn", listenerBlueArn == null ? "" : listenerBlueArn);
        lambdaEnv.put("tgNameBlue", tgBlueName == null ? "" : tgBlueName);
        lambdaEnv.put("tgNameGreen", tgGreenName == null ? "" : tgGreenName);
        lambdaEnv.put("pipelineName", appName == null ? "" : appName);
        lambdaEnv.put("ecsClusterName", clusterName == null ? "" : clusterName);
        lambdaEnv.put("ecsServiceName", serviceName == null ? "" : serviceName);
        lambdaEnv.put("deploymentConfigName", deploymentConfigName == null ? "CodeDeployDefault.ECSAllAtOnce" : deploymentConfigName );


        SingletonFunction customResource = SingletonFunction.Builder.create(this, appName+"-lambda")
            .uuid(appName+"-lambda")
            .functionName(appName+"-lambda")
            .runtime(software.amazon.awscdk.services.lambda.Runtime.PYTHON_3_9)
            .timeout(Duration.seconds(870))
            .memorySize(128)
            .code(Code.fromAsset("cdk.out/codedeploy"))
            .handler("configurator.lambda_handler")
            .environment(lambdaEnv)
            .logRetention(RetentionDays.ONE_MONTH)
            .role(customLambdaRole)
            .build();
        Provider provider   =   Provider.Builder.create(this, appName+"-codedeploy-lambda-provider")
            .onEventHandler(customResource)
            .build();
        CustomResource.Builder.create(this, appName+"-custom-resource")
            .serviceToken(provider.getServiceToken())
            .properties(lambdaEnv)
            .build();        

        return customResource;
    }    

    private Role createCodeDeployExecutionRole(final String appName, StackProps props){

        return Role.Builder.create(this, appName+"-codedeploy-exec-role")
            .assumedBy(ServicePrincipal.Builder.create("codedeploy.amazonaws.com").build())
            .description("CodeBuild Execution Role for "+appName)
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
    
    private Role createCustomLambdaRole(final String appName, StackProps props, Role codeDeployRole){        

        return Role.Builder.create(this, appName+"-custom-lambdarole")
            .inlinePolicies(new HashMap<String, PolicyDocument>(){
                private static final long serialVersionUID = 6728018370248392366L;
                {
                    put("CodeDeployPolicy", 		
                            PolicyDocument.Builder.create().statements(				
                                Arrays.asList(				
                                    PolicyStatement.Builder.create()
                                        .actions(Arrays.asList("iam:PassRole"))
                                        .effect(Effect.ALLOW)
                                        .sid("CodeDeployBlueGreenPassRole")
                                        .resources(Arrays.asList("arn:aws:iam::"+props.getEnv().getAccount()+":role/"+codeDeployRole.getRoleName()))
                                        .build())
                            ).build());

                }
            })
            .managedPolicies(Arrays.asList(                                
                ManagedPolicy.fromAwsManagedPolicyName("AWSCodeDeployFullAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole")
            ))
            .assumedBy(ServicePrincipal.Builder.create("lambda.amazonaws.com").build())
            .description("Execution Role for CustomLambda "+appName+". This lambda creates CodeDeploy application and deployment group for ECS BlueGreen")
            .path("/")
            .build();
    }          
}
