package com.example.toolchain;

import com.example.Constants;
import com.example.bootstrap.CodeDeployBootstrap;

import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.codedeploy.EcsApplication;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentGroup;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentGroupAttributes;
import software.amazon.awscdk.services.codedeploy.IEcsDeploymentGroup;
import software.amazon.awscdk.services.iam.IRole;
import software.amazon.awscdk.services.iam.Role;
import software.constructs.Construct;

/**
 * Each instance of BlueGreenDeployConfig will configure a different deployment
 * stage of the BlueGreenPipeline. As a convenience, method getStages may
 * be updated to create Production and DR environments.
 */
public class DeployConfig extends Stack {    
    
    private String deployConfig     =   null;
    private Environment env         =   null;
    private IEcsDeploymentGroup dg  =   null;
    private IRole codeDeployRole    =   null;
    private String stageName        =   null;     

    public DeployConfig(final Construct scope, final String id, final String stageName, final String deploymentConfig, StackProps props) {

        super(scope, id, props);

        this.deployConfig   =   deploymentConfig;
        this.env    =   props.getEnv();

        String stgName = stageName.replaceAll("\\s+","");
        if(!stgName.equals(stageName)){
            System.out.println("WARNING: removed spaces from stage name "+stageName+". Using: "+stgName);
        }
        this.stageName = stgName;

        dg  =  EcsDeploymentGroup.fromEcsDeploymentGroupAttributes(
            this, 
            Constants.APP_NAME+"-DeploymentGroup", 
            EcsDeploymentGroupAttributes.builder()
                .deploymentGroupName( Constants.APP_NAME+"-"+stgName )
                .application(EcsApplication.fromEcsApplicationName(
                    this, 
                    Constants.APP_NAME+"-ecs-deploy-app", 
                    Constants.APP_NAME+"-"+stgName))
                .build());  
                
        codeDeployRole  = Role.fromRoleArn(
            this, 
            "AWSCodeDeployRole"+stgName, 
            "arn:aws:iam::"+this.getAccount()+":role/"+CodeDeployBootstrap.getRoleName());
    }

    static DeployConfig createDeploymentConfig(final Construct scope, final String stageName, final String deployConfig, final String account, final String region){

        Environment env = Environment.builder().account(account).region(region).build();

        return new DeployConfig(
            scope,
            "BlueGreenDeployConfig"+stageName,            
            stageName,
            deployConfig,
            StackProps.builder()
                .env(env)
                .stackName(Constants.APP_NAME+"CodeDeploy"+stageName)
                .build());
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

    public String getStageName(){
        return this.stageName;
    }

    public String toString(){
        return env.getAccount()+"/"+env.getRegion()+"/"+this.stageName;
    } 
}