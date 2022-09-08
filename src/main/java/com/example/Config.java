package com.example;

import java.util.Arrays;
import java.util.List;

import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.constructs.Construct;

public interface Config {
    

    public static final String APP_NAME                      = "ExampleMicroservice";
    public static final String CODECOMMIT_REPO               = Config.APP_NAME;
    public static final String CODECOMMIT_BRANCH             = "master";
    public static final String TOOLCHAIN_ACCOUNT             = "111111111111";
    public static final String TOOLCHAIN_REGION              = "us-east-1";
    public static final String MICROSERVICE_PREPROD_ACCOUNT  = "111111111111";
    public static final String MICROSERVICE_PREPROD_REGION   = "us-east-1";
    

    static Environment toolchainEnv(){
	
		return Environment.builder().account(TOOLCHAIN_ACCOUNT).region(TOOLCHAIN_REGION).build();
	}    

    static List<DeploymentConfig> getStages(final Construct scope, final String appName){

        return  Arrays.asList( new DeploymentConfig[]{

            Config.createDeploymentConfig(scope,
                appName,
                "PreProd",
                DeploymentConfig.DEPLOY_LINEAR_10_PERCENT_EVERY_3_MINUTES,
                Config.MICROSERVICE_PREPROD_ACCOUNT,
                Config.MICROSERVICE_PREPROD_REGION)
    
                //add more stages to your pipeline here                
        } );
    }

    static DeploymentConfig createDeploymentConfig(final Construct scope, final String appName, final String stageName, final String deployConfig, final String account, final String region){

        return new DeploymentConfig(
            scope,
            appName,            
            stageName,
            deployConfig,
            StackProps.builder()
                .env(software.amazon.awscdk.Environment.builder()
                    .account(account)
                    .region(region)
                    .build())
                .stackName(appName+"Svc"+stageName)
                .build());
    }        

}