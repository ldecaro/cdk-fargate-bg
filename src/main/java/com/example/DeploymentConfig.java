package com.example;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.codedeploy.EcsApplication;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentGroup;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentGroupAttributes;
import software.amazon.awscdk.services.codedeploy.IEcsDeploymentGroup;
import software.amazon.awscdk.services.ecr.assets.DockerImageAsset;
import software.amazon.awscdk.services.iam.IRole;
import software.amazon.awscdk.services.iam.Role;
import software.constructs.Construct;

public class DeploymentConfig extends Stack {

    public static final String DEPLOY_LINEAR_10_PERCENT_EVERY_1_MINUTES = "CodeDeployDefault.ECSLinear10PercentEvery1Minutes";
    public static final String DEPLOY_LINEAR_10_PERCENT_EVERY_3_MINUTES = "CodeDeployDefault.ECSLinear10PercentEvery3Minutes";
    public static final String DEPLOY_CANARY_10_PERCENT_EVERY_5_MINUTES = "CodeDeployDefault.ECSCanary10percent5Minutes";
    public static final String DEPLOY_CANARY_10_PERCENT_15_MINUTES = "CodeDeployDefault.ECSCanary10percent15Minutes";
    public static final String DEPLOY_ALL_AT_ONCE = "CodeDeployDefault.ECSAllAtOnce";   

    private String deployConfig =   null;
    private Environment env =   null;
    private EnvType envType =   null;
    private IEcsDeploymentGroup dg  =   null;
    private IRole codeDeployRole    = null;

    public DeploymentConfig(Construct scope,String appName, String deploymentConfig, EnvType envType, StackProps props) {
        super(scope, appName+"-svc-"+envType.toString().toLowerCase(), props);

        this.deployConfig   =   deploymentConfig;
        this.env    =   props.getEnv();
        this.envType    =   envType;

        dg  =  EcsDeploymentGroup.fromEcsDeploymentGroupAttributes(
            this, 
            appName+"-ecsdeploymentgroup", 
            EcsDeploymentGroupAttributes.builder()
                .deploymentGroupName( appName+"-"+envType.toString().toLowerCase() )
                .application(EcsApplication.fromEcsApplicationName(
                    this, 
                    appName+"-ecs-deploy-app", 
                    appName+"-"+envType.toString().toLowerCase()))                                            
                .build());  
                
        codeDeployRole  = Role.fromRoleArn(this, "aws-code-deploy-role-"+envType.getType().toLowerCase(), "arn:aws:iam::"+this.getAccount()+":role/AWSCodeDeployRoleForBlueGreen");

        prepareDockerfile();
        //DockerImageAsset will run during synth, from inside the pipeline
        DockerImageAsset.Builder
        .create(this, appName+"-container")
        .directory("./target")//getPathDockerfile())
        .build();
    }

    public enum EnvType {

        ALPHA("Alpha"), 
        BETA("Beta"), 
        GAMMA("Gamma");

        public final String type;
        EnvType(String type){
            this.type = type;
        }

        public String getType(){
            return type;
        }
    }

    public String getDeployConfig(){
        return deployConfig;
    }

    public IEcsDeploymentGroup getEcsDeploymentGroup(){
        return dg;
    }

    public IRole getCodeDeployRole(){
        return codeDeployRole;
    }

    public void setCodeDeployRole(Role codeDeployRole){
        this.codeDeployRole = codeDeployRole;
    }

    public Environment getEnv(){
        return env;
    }

    public void setDeploymentGroup(IEcsDeploymentGroup dg){
        this.dg =   dg;
    }

    public EnvType getEnvType(){
        return this.envType;
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

    public String toString(){
        return env.getAccount()+"/"+env.getRegion()+"/"+envType;
    }    
}
