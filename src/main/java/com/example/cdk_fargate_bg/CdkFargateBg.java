package com.example.cdk_fargate_bg;

import com.example.Constants;
import com.example.bootstrap.CodeDeployBootstrap;
import com.example.cdk_fargate_bg.api.infrastructure.Api;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Stage;
import software.amazon.awscdk.services.codedeploy.EcsApplication;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentGroup;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentGroupAttributes;
import software.amazon.awscdk.services.codedeploy.IEcsDeploymentGroup;
import software.amazon.awscdk.services.iam.IRole;
import software.amazon.awscdk.services.iam.Role;
import software.constructs.Construct;

public class CdkFargateBg extends Stack {

    IEcsDeploymentGroup deploymentGroup = null;
    IRole codeDeployRole    =   null;

    public CdkFargateBg(Construct scope, String id, String deploymentConfig, StackProps props ){
        
        super(scope, id, props);

        String stageName = ((Stage)scope).getStageName();

        String envType = this.getStackName().substring(this.getStackName().indexOf(Constants.APP_NAME)+Constants.APP_NAME.length());

        Api example = new Api(
            this, 
            Constants.APP_NAME+"Api"+envType,
            deploymentConfig);

        deploymentGroup  =  EcsDeploymentGroup.fromEcsDeploymentGroupAttributes(
            this,
            Constants.APP_NAME+"-DeploymentGroup", 
            EcsDeploymentGroupAttributes.builder()
                .deploymentGroupName( Constants.APP_NAME+"-"+stageName )
                .application(EcsApplication.fromEcsApplicationName(
                    this,
                    Constants.APP_NAME+"-ecs-deploy-app", 
                    Constants.APP_NAME+"-"+stageName))
                .build());  

        codeDeployRole  = Role.fromRoleArn(
            this,
            "AWSCodeDeployRole"+stageName, 
            "arn:aws:iam::"+deploymentGroup.getEnv().getAccount()+":role/"+CodeDeployBootstrap.getRoleName());            
    
        //In case the component has more resources, 
        //ie. a dynamo table, a lambda implementing a dynamo stream and a monitoring capability
        //they should be added here, as part of the component

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

    public IRole getCodeDeployRole(){
        return this.codeDeployRole;
    }

    public IEcsDeploymentGroup getEcsDeploymentGroup(){
        return this.deploymentGroup;
    }
}