package com.example.api.infrastructure;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Stack;
import software.constructs.Construct;

public class Example extends Stack {

    public Example(Construct scope, String id, ExampleStackProps props ){

        super(scope, id, props);

        Api example = new Api(this, props.getAppName()+"-api"+this.getStackName().substring(this.getStackName().lastIndexOf("-")), props);
        
        CfnOutput.Builder.create(this, "VPC")
            .description("Arn of the VPC ")
            .value(example.getVpcArn())
            .build();

        CfnOutput.Builder.create(this, "ECSCluster")
            .description("Name of the ECS Cluster ")
            .value(example.getEcsClusterName())
            .build();            

        CfnOutput.Builder.create(this, "TaskRole")
            .description("Role name of the Task being executed ")
            .value(example.getEcsTaskRole())
            .build();            

        CfnOutput.Builder.create(this, "ExecutionRole")
            .description("Execution Role name of the Task being executed ")
            .value(example.getEcsTaskExecutionRole())
            .build();              
            
        CfnOutput.Builder.create(this, "ApplicationURL")
            .description("Application is acessible from this url")
            .value(example.getAppURL())
            .build();                             
    }
}