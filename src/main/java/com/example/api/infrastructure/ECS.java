package com.example.api.infrastructure;

import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.FargateService;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationLoadBalancer;
import software.amazon.awscdk.services.iam.IRole;
import software.amazon.awscdk.services.iam.Role;
import software.constructs.Construct;

public class ECS extends Construct{
    
    private Cluster cluster =   null;
    private FargateService service  =   null;
    private IRole taskRole  =   null;
    private IRole taskExecutionRole = null;
    private ApplicationLoadBalancer alb = null;
    private ApiHelper   helper = null;

    public ECS(Construct scope, final String id, final String strEnvType, Network ecsNetwork, ApiStackProps props ){

        super(scope,id);

        helper = new ApiHelper(this);

        final String appName    =   props.getAppName();

        Cluster cluster =   Cluster.Builder.create(this, appName+"-cluster")
            .vpc(ecsNetwork.getVpc())
            .build();

        ApplicationLoadBalancer alb     =   helper.createALB( 
            appName, 
            appName, 
            cluster, 
            strEnvType);

        Role taskRole   =   helper.createTaskRole(appName);
        Role taskExecutionRole  =   helper.createExecutionRole(appName, strEnvType);

        FargateService service = helper.createFargateService(
            appName, 
            cluster, 
            alb, 
            taskRole,
            taskExecutionRole, 
            strEnvType);

        helper.createCustomResource(
            appName+"-"+strEnvType, 
            cluster.getClusterName(), 
            service.getServiceName(), 
            props.getDeploymentConfig(), 
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
}
