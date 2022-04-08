package com.example.cdk.application;

import com.example.cdk.Toolchain.StageConfig;

public interface Service {
    
    public StageConfig getDeploymentConfig();
}
