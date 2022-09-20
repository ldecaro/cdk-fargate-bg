package com.example;

import java.util.Arrays;
import java.util.List;

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
 * Each instance of BlueGreenConfig will configure a different deployment
 * stage of the BlueGreenPipeline. As a convenience, method getStages may
 * be updated to create Production and DR environments.
 */
public class BlueGreenConfig extends Stack {  

    public static final String APP_NAME                      = "ExampleMicroservice";
    public static final String CODECOMMIT_REPO               = BlueGreenConfig.APP_NAME;
    public static final String CODECOMMIT_BRANCH             = "master";
    public static final String TOOLCHAIN_ACCOUNT             = "111111111111";
    public static final String TOOLCHAIN_REGION              = "us-east-1";
    public static final String MICROSERVICE_ACCOUNT          = "111111111111";
    public static final String MICROSERVICE_REGION           = "us-east-1";


    public static final String DEPLOY_LINEAR_10_PERCENT_EVERY_1_MINUTES = "CodeDeployDefault.ECSLinear10PercentEvery1Minutes";
    public static final String DEPLOY_LINEAR_10_PERCENT_EVERY_3_MINUTES = "CodeDeployDefault.ECSLinear10PercentEvery3Minutes";
    public static final String DEPLOY_CANARY_10_PERCENT_EVERY_5_MINUTES = "CodeDeployDefault.ECSCanary10percent5Minutes";
    public static final String DEPLOY_CANARY_10_PERCENT_15_MINUTES = "CodeDeployDefault.ECSCanary10percent15Minutes";
    public static final String DEPLOY_ALL_AT_ONCE = "CodeDeployDefault.ECSAllAtOnce";     

    private String deployConfig     =   null;
    private Environment env         =   null;
    private IEcsDeploymentGroup dg  =   null;
    private IRole codeDeployRole    =   null;
    private String stageName        =   null;    

    static Environment toolchainEnv(){
	
		return Environment.builder().account(TOOLCHAIN_ACCOUNT).region(TOOLCHAIN_REGION).build();
	}    

    static List<BlueGreenConfig> getStages(final Construct scope, final String appName){

        return  Arrays.asList( new BlueGreenConfig[]{

            BlueGreenConfig.createDeploymentConfig(
                scope,
                appName,
                "PreProd",
                BlueGreenConfig.DEPLOY_LINEAR_10_PERCENT_EVERY_3_MINUTES,
                BlueGreenConfig.MICROSERVICE_ACCOUNT,
                BlueGreenConfig.MICROSERVICE_REGION)
    
                //add more stages to your pipeline here                
                // ,
                // BlueGreenConfig.createDeploymentConfig(
                //     scope,
                //     appName,
                //     "Prod",
                //     BlueGreenConfig.DEPLOY_LINEAR_10_PERCENT_EVERY_3_MINUTES,
                //     BlueGreenConfig.MICROSERVICE_ACCOUNT,
                //     BlueGreenConfig.MICROSERVICE_REGION)   
                // ,
                // BlueGreenConfig.createDeploymentConfig(
                //     scope,
                //     appName,
                //     "DR",
                //     BlueGreenConfig.DEPLOY_LINEAR_10_PERCENT_EVERY_3_MINUTES,
                //     BlueGreenConfig.MICROSERVICE_ACCOUNT,
                //     BlueGreenConfig.MICROSERVICE_REGION)                            
        } );
    }

    private static BlueGreenConfig createDeploymentConfig(final Construct scope, final String appName, final String stageName, final String deployConfig, final String account, final String region){

        return new BlueGreenConfig(
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

    public BlueGreenConfig(final Construct scope, final String appName, final String stageName, final String deploymentConfig, StackProps props) {
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
