package com.example;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;

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
    private IEcsDeploymentGroup dg  =   null;
    private IRole codeDeployRole    = null;
    private String stageName    =  null;


    public DeploymentConfig(final Construct scope, final String appName, final String stageName, final String deploymentConfig, StackProps props) {
        super(scope, props.getStackName(), props);

        this.deployConfig   =   deploymentConfig;
        this.env    =   props.getEnv();
        // this.envType    =   envType;

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
                
        codeDeployRole  = Role.fromRoleArn(this, "aws-code-deploy-role-"+stgName.toLowerCase(), "arn:aws:iam::"+this.getAccount()+":role/"+BootstrapCodeDeploy.getRoleName());

        prepareDockerfile();
        //DockerImageAsset will run during synth, from inside the pipeline
        DockerImageAsset.Builder
        .create(this, appName+"-container")
        .directory("./target")
        .build();
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

    /**
     * Copy Dockerfile from /runtime directory to /target
     */
    void prepareDockerfile(){

        if(! new File("./target/Dockerfile").exists() ){

            String dest = "./target/Dockerfile";
            String orig = "./target/classes/"+this.getClass().getName().substring(0, this.getClass().getName().lastIndexOf(".")).replace(".", "/");
            orig += "/api/runtime/Dockerfile";

            try{
                Files.copy(Paths.get(orig), Paths.get(dest), StandardCopyOption.REPLACE_EXISTING);
            }catch(IOException ioe){
                System.out.println("Could not copy Dockerfile from Green app from: "+orig+" to "+dest+". Msg: "+ioe.getMessage());
            }    
        }    
    }

    public String toString(){
        return env.getAccount()+"/"+env.getRegion()+"/"+this.stageName;
    }   

    private static DeploymentConfig createDeploymentConfig(final Construct scope, final String appName, final String stageName, final String deployConfig, final String account, final String region){

        return new DeploymentConfig(
            scope,
            appName,            
            stageName,
            deployConfig,
            StackProps.builder()
                .env(Environment.builder()
                    .account(account)
                    .region(region)
                    .build())
                .stackName(appName+"Svc"+stageName)
                .build());
    }    
    
    public static final String getAppName(){

        String appName = "ExampleMicroservice";
        return System.getenv("CDK_PIPELINE_APP_NAME") == null ? appName : System.getenv("CDK_PIPELINE_APP_NAME");
    }

    public static final Environment getToolchainEnv(){

        String account = "111111111111";
        String region = "us-east-1";

        String envAccount = System.getenv("CDK_DEPLOY_ACCOUNT") == null ? System.getenv("AWS_DEFAULT_ACCOUNT") : System.getenv("CDK_DEPLOY_ACCOUNT");
        String envRegion = System.getenv("CDK_DEPLOY_REGION") == null ? System.getenv("AWS_DEFAULT_REGION") : System.getenv("AWS_DEFAULT_REGION");

        account = envAccount == null ? account : envAccount;
        region = envRegion == null ? region : envRegion;

        if( "111111111111".equals(account) ){ 
            System.out.println("ERROR: Toolchain account and region not configured. Please use environment variables PIPELINE_TOOLCHAIN_ACCOUNT and PIPELINE_TOOLCHAIN_REGION or configure class com.example.DeploymentConfig.java");
            throw new RuntimeException("ERROR: Toolchain account and region not configured. Please use environment variables PIPELINE_TOOLCHAIN_ACCOUNT and PIPELINE_TOOLCHAIN_REGION or configure class com.example.DeploymentConfig.java");
        }else{
            System.out.println("Using Toolchain account/region: "+account+"/"+region);
        }

        return Environment.builder().account(account).region(region).build();
    }

    public static final List<DeploymentConfig> getStages(final Construct scope, final String appName){

        return  Arrays.asList( new DeploymentConfig[]{

            createDeploymentConfig(scope,
                appName,
                "SIT",
                DeploymentConfig.DEPLOY_ALL_AT_ONCE,
                // "111111111111",
                System.getenv("CDK_DEPLOY_ACCOUNT"),
                // System.getenv("CDK_DEPLOY_REGION")
                "us-east-2"),

            createDeploymentConfig(scope,
                appName,
                "UAT",
                DeploymentConfig.DEPLOY_LINEAR_10_PERCENT_EVERY_1_MINUTES, 
                // "222222222222",
                "742584497250",
                "us-east-1"),
                
            createDeploymentConfig(scope,
                appName,
                "Prod",
                DeploymentConfig.DEPLOY_LINEAR_10_PERCENT_EVERY_1_MINUTES, 
                // "222222222222",
                "279211433385",
                "us-east-1")                   
        } );
    }    
}
