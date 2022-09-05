package com.example;

import java.util.Map;

import org.jetbrains.annotations.Nullable;

import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

public class ToolchainStackProps implements StackProps {

    private String appName;
    private String gitRepo;     
    private Environment env;
    private Map<String,String> tags;
    private Boolean terminationProtection   =   Boolean.FALSE;   

    @Override
    public @Nullable Map<String, String> getTags() {
        return tags;
    }

    @Override
    public @Nullable Boolean getTerminationProtection() {
        return this.terminationProtection;
    }

    @Override
    public @Nullable String getDescription() {
        return "Toolchain of microservice: "+getAppName();
    }

    public String getAppName(){
        return appName;
    }

    public Environment getEnv(){
        return env;
    }

    public String getGitRepo() {
        return gitRepo;
    }


    public ToolchainStackProps(String appName, String gitRepo, Environment env,Map<String,String> tags, Boolean terminationProtection){
        this.appName = appName;
        this.env = env;
        this.tags = tags;
        this.terminationProtection = terminationProtection;
        this.gitRepo = gitRepo;         
    }

    public static Builder builder(){
        return new Builder();
    }
    static class Builder{

        private String appName;
        private String gitRepo;
        private Environment env;
        private Map<String,String> tags;
        private Boolean terminationProtection = Boolean.FALSE;

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

        public Builder gitRepo(String gitRepo){
            this.gitRepo = gitRepo;
            return this;
        }    

        public ToolchainStackProps build(){
            return new ToolchainStackProps(appName, gitRepo, env, tags, terminationProtection);
        }
    }
}