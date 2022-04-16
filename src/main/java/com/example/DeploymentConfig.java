package com.example;

import software.amazon.awscdk.Environment;
import software.amazon.awscdk.services.codedeploy.IEcsDeploymentGroup;
import software.amazon.awscdk.services.iam.Role;

public class DeploymentConfig{

    public static final String DEPLOY_LINEAR_10_PERCENT_EVERY_1_MINUTES = "CodeDeployDefault.ECSLinear10PercentEvery1Minutes";
    public static final String DEPLOY_LINEAR_10_PERCENT_EVERY_3_MINUTES = "CodeDeployDefault.ECSLinear10PercentEvery3Minutes";
    public static final String DEPLOY_CANARY_10_PERCENT_EVERY_5_MINUTES = "CodeDeployDefault.ECSCanary10percent5Minutes";
    public static final String DEPLOY_CANARY_10_PERCENT_15_MINUTES = "CodeDeployDefault.ECSCanary10percent15Minutes";
    public static final String DEPLOY_ALL_AT_ONCE = "CodeDeployDefault.ECSAllAtOnce";            

    private String deployConfig;
    private IEcsDeploymentGroup dg;
    private Role codeDeployRole;
    private Environment env;
    private EnvType envType;
    
    public enum EnvType {

        ALPHA("Alpha"), 
        BETA("Beta"), 
        GAMMA("Gamma");

        public final String type;
        EnvType(String type){
            this.type = type;
        }

        public String getType(){
            return type;
        }
    }

    public String getDeployConfig(){
        return deployConfig;
    }

    public IEcsDeploymentGroup getEcsDeploymentGroup(){
        return dg;
    }

    public Role getCodeDeployRole(){
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

    public DeploymentConfig(final String deployConfig, Environment env, EnvType envType){
        this.deployConfig   =   deployConfig;
        this.env = env;
        this.envType    =   envType;
    }

    public EnvType getEnvType(){
        return this.envType;
    }

    public DeploymentConfig(String deployConfig, Environment env, EnvType envType, Role codeDeployRole, IEcsDeploymentGroup dg){
        this.codeDeployRole =   codeDeployRole;
        this.dg    =    dg;
        this.deployConfig   =   deployConfig;
        this.env    =   env;
        this.envType    =   envType;
    }

    public String toString(){
        return env.getAccount()+"/"+env.getRegion()+"/"+envType;
    }
}
