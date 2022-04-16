package com.example.api.infrastructure;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Stack;
import software.constructs.Construct;

public class Api extends Stack {

    private ApiHelper helper    =   new ApiHelper(this);       
    
    public Api(Construct scope, String id, ApiStackProps props ){

        super(scope, id, props);
        String appName          = props.getAppName();
        String strEnvType       =   id.split("-")[id.split("-").length-1].toLowerCase();

        Network ecsNetwork = new Network(this, appName+"-api-network", appName );

        ECS ecs = new ECS(this, appName+"-api-ecs", strEnvType, ecsNetwork, props ); 
        
        CfnOutput.Builder.create(this, "VPC")
            .description("Arn of the VPC ")
            .value(ecsNetwork.getVpc().getVpcArn())
            .build();

        CfnOutput.Builder.create(this, "ECSCluster")
            .description("Name of the ECS Cluster ")
            .value(ecs.getCluster().getClusterName())
            .build();            

        CfnOutput.Builder.create(this, "TaskRole")
            .description("Role name of the Task being executed ")
            .value(ecs.getTaskRole().getRoleName())
            .build();            

        CfnOutput.Builder.create(this, "ExecutionRole")
            .description("Execution Role name of the Task being executed ")
            .value(ecs.getTaskExecutionRole().getRoleName())
            .build();              
            
        CfnOutput.Builder.create(this, "ApplicationURL")
            .description("Application is acessible from this url")
            .value("http://"+ecs.getALB().getLoadBalancerDnsName())
            .build();                
                        
    }

    public String getListenerBlueArn() {
        return helper.getListenerBlueArn();
    }


    public String getListenerGreenArn() {
        return helper.getListenerGreenArn();
    }


    public String getTgBlueName() {
        return helper.getTgBlueName();
    }


    public String getTgGreenName() {
        return helper.getTgGreenName();
    } 
}