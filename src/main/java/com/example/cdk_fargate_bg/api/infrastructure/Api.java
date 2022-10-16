package com.example.cdk_fargate_bg.api.infrastructure;

import static com.example.Constants.APP_NAME;
import com.example.cdk_fargate_bg.compute.infrastructure.ECS;
import com.example.cdk_fargate_bg.network.infrastructure.Network;

import software.amazon.awscdk.services.ecr.assets.DockerImageAsset;
import software.constructs.Construct;

public class Api extends Construct {
    
    private String vpcArn = null;
    private String ecsClusterName = null;
    private String ecsTaskRole = null;
    private String ecsTaskExecutionRole = null;
    private String appURL = null;
    
    public Api(final Construct scope, final String id, final String deploymentConfig){

        super(scope, id);
        String strEnvType   =   id.split("Api")[id.split("Api").length-1];
        
        DockerImageAsset.Builder.create(this, APP_NAME+"-container")
            .directory("./target")
            .build();

        Network ecsNetwork = new Network(
            this, 
            "Network", 
            APP_NAME );

        ECS ecs = new ECS(
            this, 
            "ECS", 
            deploymentConfig, 
            strEnvType, 
            ecsNetwork); 
        
        this.vpcArn =   ecsNetwork.getVpc().getVpcArn();
        this.ecsClusterName = ecs.getCluster().getClusterName();
        this.ecsTaskRole = ecs.getTaskRole().getRoleName();
        this.ecsTaskExecutionRole = ecs.getTaskExecutionRole().getRoleName();
        this.appURL = "http://"+ecs.getALB().getLoadBalancerDnsName();     
    }

    public String getVpcArn(){
        return this.vpcArn;
    }

    public String getEcsClusterName(){
        return this.ecsClusterName;
    }

    public String getEcsTaskRole(){
        return this.ecsTaskRole;
    }

    public String getEcsTaskExecutionRole(){
        return this.ecsTaskExecutionRole;
    }

    public String getAppURL(){
        return this.appURL;
    }
}