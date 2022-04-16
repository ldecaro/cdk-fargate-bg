package com.example;

import com.example.api.infrastructure.Api;
import com.example.api.infrastructure.ApiStackProps;

import software.amazon.awscdk.Stage;
import software.constructs.Construct;

class Deployment extends Stage {

    public Deployment(Construct scope, String id, String appName, DeploymentConfig deploymentConfig) {

        super(scope, id );

        new Api(this, 
            appName+"-api-"+deploymentConfig.getEnvType().toString().toLowerCase(), 
            ApiStackProps.builder()
                .appName(appName)
                .deploymentConfig(deploymentConfig.getDeployConfig())
                .stackName(appName+"-api-"+deploymentConfig.getEnvType().toString().toLowerCase())
                .env(deploymentConfig.getEnv())
                .build());
    }
}