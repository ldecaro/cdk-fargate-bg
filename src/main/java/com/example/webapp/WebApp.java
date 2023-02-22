package com.example.webapp;

import com.example.Constants;
import com.example.webapp.compute.infrastructure.Compute;
import com.example.webapp.network.infrastructure.Network;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.services.codedeploy.IEcsDeploymentConfig;
import software.constructs.Construct;

public class WebApp extends Stack {

    public WebApp(Construct scope, String id, IEcsDeploymentConfig deploymentConfig, StackProps props){
    
        super(scope, id, props);

        String envType = this.getStackName().substring(this.getStackName().indexOf(Constants.APP_NAME)+Constants.APP_NAME.length());

        Network ecsNetwork = new Network(
            this, 
            "Network");

        Compute ecs = new Compute(
            this, 
            "ECS", 
            deploymentConfig, 
            envType, 
            ecsNetwork);

        // In case the component has more resources, 
        // ie. a dynamo table, a lambda implementing a dynamo stream and a monitoring capability
        // they should be added here, as part of the component            

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
}