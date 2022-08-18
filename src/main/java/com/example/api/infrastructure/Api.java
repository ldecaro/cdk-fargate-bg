package com.example.api.infrastructure;

import software.amazon.awscdk.services.ecr.assets.DockerImageAsset;
import software.constructs.Construct;

public class Api extends Construct {
    
    private String vpcArn = null;
    private String ecsClusterName = null;
    private String ecsTaskRole = null;
    private String ecsTaskExecutionRole = null;
    private String appURL = null;
    
    public Api(final Construct scope, final String id, ExampleStackProps props){

        super(scope, id);
        String appName          = props.getAppName();
        String strEnvType       =   id.split("Api")[id.split("Api").length-1];

        DockerImageAsset.Builder
        .create(scope, appName+"-container")
        .directory("./target")
        .build();

        Network ecsNetwork = new Network(scope, appName+"-api-network", appName );

        ECS ecs = new ECS(scope, appName+"-api-ecs", strEnvType, ecsNetwork, props ); 
        
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
