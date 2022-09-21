package com.example.toolchain;

import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.constructs.Construct;

public class Toolchain extends Stack {

    public static final String APP_NAME                      = "ExampleMicroservice";
    public static final String CODECOMMIT_REPO               = Toolchain.APP_NAME;
    public static final String CODECOMMIT_BRANCH             = "master";
    public static final String TOOLCHAIN_ACCOUNT             = "111111111111";
    public static final String TOOLCHAIN_REGION              = "us-east-1";
    public static final String MICROSERVICE_ACCOUNT          = "111111111111";
    public static final String MICROSERVICE_REGION           = "us-east-1";

    public static Environment toolchainEnv(){
	
		return Environment.builder().account(TOOLCHAIN_ACCOUNT).region(TOOLCHAIN_REGION).build();
	}  

    public Toolchain(final Construct scope, final String id, final String appName, final String gitRepo, final StackProps props) throws Exception {

        super(scope, id, props);

        new BlueGreenPipeline(
            this,
            "BlueGreenPipeline", 
            appName, 
            gitRepo, 
            BlueGreenPipelineConfig.getStages(
                scope, 
                appName));
    }
}