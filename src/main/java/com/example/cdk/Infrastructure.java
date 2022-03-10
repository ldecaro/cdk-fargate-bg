package com.example.cdk;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.example.cdk.Pipeline.StageConfig;

import org.jetbrains.annotations.Nullable;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CustomResource;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.customresources.Provider;
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
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.PolicyDocument;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.SingletonFunction;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.constructs.Construct;

public class Infrastructure extends Stack {

    private String listenerBlueArn  = null;
    private String listenerGreenArn = null;
    private String tgBlueName       = null;
    private String tgGreenName      = null;

    private static final String    ECS_TASK_CPU = "1024";
    private static final String    ECS_TASK_MEMORY = "2048";
    private static final Integer   ECS_CONTAINER_MEMORY_RESERVATION = 256;
    private static final Integer   ECS_CONTAINER_MEMORY_LIMIT = 512;
    private static final Integer   ECS_TASK_CONTAINER_PORT = 8080;
    private static final Integer   ECS_TASK_CONTAINER_HOST_PORT = 8080;    

    private SecurityGroup sg;
    
    public Infrastructure(Construct scope, String id, ECSStackProps props ){

        super(scope, id, props);
        String appName          = props.getAppName();
        String strEnvType       =   id.split("-")[id.split("-").length-1].toLowerCase();
        //configuration between linear and blue/green
        String deploymentConfigName =   props.getDeploymentConfig();

        Vpc vpc = Vpc.Builder.create(this, appName+"-vpc") 
            .maxAzs(2)
            .natGateways(1)
            .enableDnsHostnames(Boolean.TRUE)
            .enableDnsSupport(Boolean.TRUE)            
            .build();
        
        SecurityGroup sg    =   SecurityGroup.Builder
            .create(this, appName+"-sg")
            .vpc(vpc)
            .allowAllOutbound(Boolean.TRUE)
            .build();

        sg.addIngressRule(Peer.anyIpv4(), Port.allTcp());
        sg.addIngressRule(Peer.anyIpv4(), Port.allUdp());
        
        Cluster cluster =   Cluster.Builder.create(this, appName+"-cluster")
            .vpc(vpc)
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
            .roleName(appName+"-"+strEnvType)
            .assumedBy(ServicePrincipal.Builder.create("ecs-tasks.amazonaws.com").build())
            .managedPolicies(Arrays.asList(
                ManagedPolicy.fromManagedPolicyArn(
                    this, 
                    "ecsTaskExecutionManagedPolicy", 
                    "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"),
                ManagedPolicy.fromAwsManagedPolicyName("CloudWatchAgentServerPolicy")
            )).build();

        ApplicationLoadBalancer alb     =   createALB(appName, appName, cluster, strEnvType);

        FargateService service = createFargateService(appName, cluster, alb, appName, taskRole, executionRole, strEnvType);
        createCustomResource(
            appName+"-"+strEnvType, 
            cluster.getClusterName(), 
            service.getServiceName(), 
            deploymentConfigName, 
            props);                    

         CfnOutput.Builder.create(this, "VPC")
            .description("Arn of the VPC ")
            .value(vpc.getVpcArn())
            .build();

        CfnOutput.Builder.create(this, "ECSCluster")
            .description("Name of the ECS Cluster ")
            .value(cluster.getClusterName())
            .build();            

        CfnOutput.Builder.create(this, "TaskRole")
            .description("Role name of the Task being executed ")
            .value(taskRole.getRoleName())
            .build();            

        CfnOutput.Builder.create(this, "ExecutionRole")
            .description("Execution Role name of the Task being executed ")
            .value(taskRole.getRoleName())
            .build();              
            
        CfnOutput.Builder.create(this, "ApplicationURL")
            .description("Application is acessible from this url")
            .value("http://"+alb.getLoadBalancerDnsName())
            .build();                
                        
    }

    private ApplicationLoadBalancer createALB(final String appName, final String serviceName, final Cluster cluster, final String strEnvType){
        
        ApplicationLoadBalancer alb = ApplicationLoadBalancer.Builder.create(this, appName+"-LB")
            .loadBalancerName(appName+"-alb").vpc(cluster.getVpc()).internetFacing(true)
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

        ApplicationTargetGroup tgGreen   =   ApplicationTargetGroup.Builder.create(this, appName+"-green-tg")
            .protocol(ApplicationProtocol.HTTP)
            .targetGroupName(appName+"-"+strEnvType+"-Green")
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

    private FargateService createFargateService(String appName, Cluster cluster, ApplicationLoadBalancer lb, String serviceName, Role taskRole, Role executionRole, String strEnvType ){

        FargateService service  =   FargateService.Builder.create(this, serviceName+"-fargateSvc")
            .desiredCount(1)
            .cluster(cluster)
            .serviceName(serviceName)
            .deploymentController(DeploymentController.builder().type(DeploymentControllerType.CODE_DEPLOY).build())
            .securityGroups(Arrays.asList(this.sg))
            .taskDefinition(createECSTask(appName, new HashMap<String,String>(), serviceName, taskRole, executionRole))
            .build();
  
        ApplicationListener listener = lb.getListeners().get(0);

        String tgBlueName = appName+"-"+strEnvType+"-Blue";
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
            .cpu(Infrastructure.ECS_TASK_CPU)
            .memoryMiB(Infrastructure.ECS_TASK_MEMORY)
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
                    .directory("./blue-green/blue-app")
                    .build()))
            .essential(Boolean.TRUE)
            .portMappings(Arrays.asList(
                PortMapping.builder()
                    .containerPort(Infrastructure.ECS_TASK_CONTAINER_PORT)
                    .hostPort(Infrastructure.ECS_TASK_CONTAINER_HOST_PORT)
                    .protocol(Protocol.TCP)
                .build()))          
            .environment(env)
            .build());            

        return taskDef;
    }

    private Role createCodeDeployExecutionRole(final String appName, StackProps props){

        return Role.Builder.create(this, appName+"-codedeploy-exec-role")
            // .roleName(appName+"-codedeploy-deployment-group")
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
            // .roleName(appName+"-custom-lambda-role")
            .assumedBy(ServicePrincipal.Builder.create("lambda.amazonaws.com").build())
            .description("Execution Role for CustomLambda "+appName+". This lambda creates CodeDeploy application and deployment group for ECS BlueGreen")
            .path("/")
            .build();
    }   
    
    private void createCustomResource(String appName, String clusterName, String serviceName, String deploymentConfigName, StackProps props){

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
        lambdaEnv.put("deploymentConfigName", deploymentConfigName == null ? StageConfig.DEPLOY_ALL_AT_ONCE : deploymentConfigName );


        SingletonFunction customResource = SingletonFunction.Builder.create(this, appName+"-codedeploy-blue-green-lambda")
            .uuid(appName+"-codedeploy-blue-green-lambda")
            .functionName(appName+"-codedeploy-blue-green-lambda")
            .runtime(software.amazon.awscdk.services.lambda.Runtime.PYTHON_3_9)
            .timeout(Duration.seconds(870))
            .memorySize(128)
            .code(Code.fromAsset("lambda"))
            .handler("lambda_function.lambda_handler")
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

    public static class ECSStackProps implements StackProps {

        String appName              =   null;
        String deploymentConfig     =   null;
        Environment env             =   null;
        Map<String,String> tags     =   null;
        Boolean terminationProtection   =   Boolean.FALSE;
        String stackName    =   null;

        @Override
        public @Nullable Map<String, String> getTags() {
            return StackProps.super.getTags();
        }

        @Override
        public @Nullable Boolean getTerminationProtection() {
            return this.terminationProtection;
        }

        @Override
        public @Nullable String getDescription() {
            return "ECSStack. Application: "+getAppName();
        }

        public String getAppName(){
            return appName;
        }

        public Environment getEnv(){
            return env;
        }

        public String getDeploymentConfig(){
            return deploymentConfig;
        }

        @Override
        public String getStackName(){
            return stackName;
        }        


        public ECSStackProps(String appName, String deploymenConfig, Environment env, Map<String,String> tags, Boolean terminationProtection, String stackName){
            this.appName = appName;
            this.env = env;
            this.tags = tags;
            this.terminationProtection = terminationProtection;
            this.deploymentConfig = deploymenConfig;
            this.stackName  =   stackName;
        }

        public static Builder builder(){
            return new Builder();
        }
        static class Builder{


            private String appName          =   null;;
            private String deploymentConfig =   null;
            private Environment env         =   null;
            private Map<String,String> tags =   null;
            private Boolean terminationProtection = Boolean.FALSE;
            private String stackName        =   null;

            public Builder appName(String appName){
                this.appName = appName;
                return this;
            }

            public Builder env(Environment env){
                this.env = env;
                return this;
            }

            public Builder tags(Map<String, String> tags){
                this.tags = tags;
                return this;
            }

            public Builder terminationProtection(Boolean terminationProtection){
                this.terminationProtection = terminationProtection;
                return this;
            }

            public Builder deploymentConfig(String deploymentConfig){
                this.deploymentConfig = deploymentConfig;
                return this;
            }

            public Builder stackName(final String stackName){
                this.stackName = stackName;
                return this;
            }            

            public ECSStackProps build(){
                return new ECSStackProps(appName, deploymentConfig, env, tags, terminationProtection, stackName);
            }
        }
    }    
}