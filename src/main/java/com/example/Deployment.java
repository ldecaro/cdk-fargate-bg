package com.example;

import com.example.api.infrastructure.Example;
import com.example.api.infrastructure.ExampleStackProps;

import software.amazon.awscdk.Stage;
import software.constructs.Construct;

class Deployment extends Stage {

    public Deployment(Construct scope, String id, String appName, DeploymentConfig deploymentConfig) {

        super(scope, id );

        new Example(this, 
            appName+"-api-"+deploymentConfig.getEnvType().toString().toLowerCase(), 
            ExampleStackProps.builder()
                .appName(appName)
                .deploymentConfig(deploymentConfig.getDeployConfig())
                .stackName(appName+"-"+deploymentConfig.getEnvType().toString().toLowerCase())
                .description("Application "+appName+"-"+deploymentConfig.getEnvType().getType().toLowerCase())
                .env(deploymentConfig.getEnv())
                .build());
    }
}