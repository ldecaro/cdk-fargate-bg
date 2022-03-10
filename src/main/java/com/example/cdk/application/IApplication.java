package com.example.cdk.application;

import com.example.cdk.Pipeline.StageConfig;

public interface IApplication {
    
    public StageConfig getDeploymentConfig();
}
