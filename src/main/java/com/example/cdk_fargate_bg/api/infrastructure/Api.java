package com.example.cdk_fargate_bg.api.infrastructure;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ecr.assets.DockerImageAsset;
import software.constructs.Construct;

public class Api extends Construct {
    
    private String vpcArn = null;
    private String ecsClusterName = null;
    private String ecsTaskRole = null;
    private String ecsTaskExecutionRole = null;
    private String appURL = null;
    
    public Api(final Construct scope, final String id, final String appName, final String deploymentConfig, StackProps props){

        super(scope, id);
        String strEnvType       =   id.split("Api")[id.split("Api").length-1];

        moveDockerfile();
        
        DockerImageAsset.Builder.create(scope, appName+"-container")
            .directory("./target")
            .build();

        Network ecsNetwork = new Network(
            scope, 
            appName+"-api-network", 
            appName );

        ECS ecs = new ECS(
            scope, 
            appName+"-api-ecs", 
            appName, 
            deploymentConfig, 
            strEnvType, 
            ecsNetwork, 
            props ); 
        
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

    /**
     * Move dockerfile from the internal directory /runtine to $PROJECT_HOME/target
     */
    void moveDockerfile(){

        if(! new File("./target/Dockerfile").exists() ){

            String dest = "./target/Dockerfile";
            String orig = "./target/classes/"+this.getClass().getName().substring(0, this.getClass().getName().lastIndexOf(".")).replace(".", "/");
            orig += "/../runtime/Dockerfile";

            try{
                Files.copy(Paths.get(orig), Paths.get(dest), StandardCopyOption.REPLACE_EXISTING);
            }catch(IOException ioe){
                System.out.println("Could not copy Dockerfile from Green app from: "+orig+" to "+dest+". Msg: "+ioe.getMessage());
            }    
        }    
    }    
}
