package com.example.api.infrastructure;

import java.util.Map;

import org.jetbrains.annotations.Nullable;

import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

public  class CrossAccountServiceProps implements StackProps {

    Environment envPipeline;
    Environment env;
    Map<String,String> tags;
    Boolean terminationProtection;
    String stackName;

    @Override
    public @Nullable Map<String, String> getTags() {
        return StackProps.super.getTags();
    }

    @Override
    public @Nullable Boolean getTerminationProtection() {
        return this.terminationProtection;
    }

    @Override
    public @Nullable String getDescription() {
        return "ApplicationStack";
    }

    public Environment getEnvPipeline(){
        return envPipeline;
    }

    public Environment getEnv() {
        return env;
    }
    
    @Override
    public String getStackName(){
        return stackName;
    }

    public CrossAccountServiceProps(Environment envPipeline, Environment env, Map<String,String> tags, Boolean terminationProtection, String stackName){
        this.envPipeline = envPipeline;
        this.env = env;
        this.tags = tags;
        this.terminationProtection = terminationProtection;
        this.stackName  =   stackName;
    }

    public static Builder builder(){
        return new Builder();
    }
    public static class Builder{

        private Environment envPipeline;
        private Environment env;
        private Map<String,String> tags;
        private Boolean terminationProtection;
        private String stackName;

        public Builder envPipeline(Environment envPipeline){
            this.envPipeline = envPipeline;
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

        public Builder env(Environment env){
            this.env = env;
            return this;
        }

        public Builder stackName(final String stackName){
            this.stackName = stackName;
            return this;
        }

        public CrossAccountServiceProps build(){
            return new CrossAccountServiceProps(envPipeline, env, tags, terminationProtection, stackName);
        }
    }
}      
