package com.example.api.infrastructure;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.services.ecr.assets.DockerImageAsset;
import software.constructs.Construct;

public class Api extends Stack {

    private ApiHelper helper    =   new ApiHelper(this);       
    
    public Api(Construct scope, String id, ApiStackProps props ){

        super(scope, id, props);
        String appName          = props.getAppName();
        String strEnvType       =   id.split("-")[id.split("-").length-1].toLowerCase();

        DockerImageAsset.Builder
        .create(this, appName+"-container")
        .directory("./target")//getPathDockerfile())
        .build();

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

    /**
     * Copy Dockerfile from /runtime directory to /target
     */
    void prepareDockerfile(){

        if(! new File("./target/Dockerfile").exists() ){

            String dest = "./target/Dockerfile";
            String orig = "./target/classes/"+this.getClass().getName().substring(0, this.getClass().getName().lastIndexOf(".")).replace(".", "/");
            orig += "/../runtime/Dockerfile";

            try{
                Files.copy(Paths.get(orig), Paths.get(dest), StandardCopyOption.REPLACE_EXISTING);
            }catch(IOException ioe){
                System.out.println("Could not copy Dockerfile from Green app from: "+orig+" to "+dest+"Msg: "+ioe.getMessage());
            }    
        }    
    }    
}