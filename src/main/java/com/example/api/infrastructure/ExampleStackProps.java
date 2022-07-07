package com.example.api.infrastructure;

import java.util.Map;

import org.jetbrains.annotations.Nullable;

import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

public class ExampleStackProps implements StackProps {

    private String appName              =   null;
    private String deploymentConfig     =   null;
    private Environment env             =   null;
    private Map<String,String> tags     =   null;
    private Boolean terminationProtection   =   Boolean.FALSE;
    private String stackName    =   null;
    private String description  =   null;

    @Override
    public @Nullable Map<String, String> getTags() {
        return tags;
    }

    @Override
    public @Nullable Boolean getTerminationProtection() {
        return this.terminationProtection;
    }

    public String getAppName(){
        return appName;
    }

    public Environment getEnv(){
        return env;
    }

    public String getDeploymentConfig(){
        return deploymentConfig;
    }

    @Override
    public String getStackName(){
        return stackName;
    }     
    @Override
    public String getDescription(){
        return description;
    }


    public ExampleStackProps(String appName, String deploymenConfig, Environment env, Map<String,String> tags, Boolean terminationProtection, String stackName, String description){
        this.appName = appName;
        this.env = env;
        this.tags = tags;
        if(StackProps.super.getTags()!=null)
            this.tags.putAll(StackProps.super.getTags());
        this.terminationProtection = terminationProtection;
        this.deploymentConfig = deploymenConfig;
        this.stackName  =   stackName;
        this.description    =   description;
    }

    public static Builder builder(){
        return new Builder();
    }
    public static class Builder{


        private String appName          =   null;;
        private String deploymentConfig =   null;
        private Environment env         =   null;
        private Map<String,String> tags =   null;
        private Boolean terminationProtection = Boolean.FALSE;
        private String stackName        =   null;
        private String description      =   null;

        public Builder appName(String appName){
            this.appName = appName;
            return this;
        }

        public Builder env(Environment env){
            this.env = env;
            return this;
        }

        public Builder tags(Map<String, String> tags){
            this.tags = tags;
            return this;
        }

        public Builder terminationProtection(Boolean terminationProtection){
            this.terminationProtection = terminationProtection;
            return this;
        }

        public Builder deploymentConfig(String deploymentConfig){
            this.deploymentConfig = deploymentConfig;
            return this;
        }

        public Builder stackName(final String stackName){
            this.stackName = stackName;
            return this;
        } 
        
        public Builder description(final String description){
            this.description = description;
            return this;
        }

        public ExampleStackProps build(){
            return new ExampleStackProps(appName, deploymentConfig, env, tags, terminationProtection, stackName, description);
        }
    }
}   
