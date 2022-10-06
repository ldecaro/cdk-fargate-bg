package com.example.toolchain;

import java.util.Arrays;
import java.util.List;

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
public class BlueGreenDeployConfig extends Stack {  //BlueGreenDeployConfig


    public static final String DEPLOY_LINEAR_10_PERCENT_EVERY_1_MINUTES = "CodeDeployDefault.ECSLinear10PercentEvery1Minutes";
    public static final String DEPLOY_LINEAR_10_PERCENT_EVERY_3_MINUTES = "CodeDeployDefault.ECSLinear10PercentEvery3Minutes";
    public static final String DEPLOY_CANARY_10_PERCENT_EVERY_5_MINUTES = "CodeDeployDefault.ECSCanary10percent5Minutes";
    public static final String DEPLOY_CANARY_10_PERCENT_15_MINUTES = "CodeDeployDefault.ECSCanary10percent15Minutes";
    public static final String DEPLOY_ALL_AT_ONCE = "CodeDeployDefault.ECSAllAtOnce";     
  

    static List<BlueGreenDeployConfig> getStages(final Construct scope, final String appName){

        return  Arrays.asList( new BlueGreenDeployConfig[]{

            BlueGreenDeployConfig.createDeploymentConfig(
                scope,
                appName,
                "PreProd",
                BlueGreenDeployConfig.DEPLOY_LINEAR_10_PERCENT_EVERY_3_MINUTES,
                Toolchain.MICROSERVICE_ACCOUNT,
                Toolchain.MICROSERVICE_REGION)
    
                //add more stages to your pipeline here                
                // ,
                // BlueGreenDeployConfig.createDeploymentConfig(
                //     scope,
                //     appName,
                //     "Prod",
                //     BlueGreenDeployConfig.DEPLOY_LINEAR_10_PERCENT_EVERY_3_MINUTES,
                //     Toolchain.MICROSERVICE_ACCOUNT,
                //     Toolchain.MICROSERVICE_REGION)   
                // ,
                // BlueGreenDeployConfig.createDeploymentConfig(
                //     scope,
                //     appName,
                //     "DR",
                //     BlueGreenDeployConfig.DEPLOY_LINEAR_10_PERCENT_EVERY_3_MINUTES,
                //     Toolchain.MICROSERVICE_ACCOUNT,
                //     Toolchain.MICROSERVICE_REGION)                            
        } );
    }    

    private static BlueGreenDeployConfig createDeploymentConfig(final Construct scope, final String appName, final String stageName, final String deployConfig, final String account, final String region){

        return new BlueGreenDeployConfig(
            scope,
            appName,            
            stageName,
            deployConfig,
            StackProps.builder()
                .env(software.amazon.awscdk.Environment.builder()
                    .account(account)
                    .region(region)
                    .build())
                .stackName(appName+"CodeDeploy"+stageName)
                .build());
    }    
    
    private String deployConfig     =   null;
    private Environment env         =   null;
    private IEcsDeploymentGroup dg  =   null;
    private IRole codeDeployRole    =   null;
    private String stageName        =   null;     

    public BlueGreenDeployConfig(final Construct scope, final String appName, final String stageName, final String deploymentConfig, StackProps props) {

        super(scope, props.getStackName(), props);

        this.deployConfig   =   deploymentConfig;
        this.env    =   props.getEnv();

        String stgName = stageName.replaceAll("\\s+","");
        if(!stgName.equals(stageName)){
            System.out.println("WARNING: removed spaces from stage name "+stageName+". Using: "+stgName);
        }
        this.stageName = stgName;


        dg  =  EcsDeploymentGroup.fromEcsDeploymentGroupAttributes(
            this, 
            appName+"-ecsdeploymentgroup", 
            EcsDeploymentGroupAttributes.builder()
                .deploymentGroupName( appName+"-"+stgName )
                .application(EcsApplication.fromEcsApplicationName(
                    this, 
                    appName+"-ecs-deploy-app", 
                    appName+"-"+stgName))
                .build());  
                
        codeDeployRole  = Role.fromRoleArn(this, "aws-code-deploy-role-"+stgName.toLowerCase(), "arn:aws:iam::"+this.getAccount()+":role/"+CodeDeployBootstrap.getRoleName());
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
