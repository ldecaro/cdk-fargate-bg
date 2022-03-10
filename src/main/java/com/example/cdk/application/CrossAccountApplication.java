package com.example.cdk.application;

import java.util.Arrays;
import java.util.Map;

import com.example.cdk.Pipeline.StageConfig;
import com.example.cdk.Pipeline.StageConfig.EnvType;

import org.jetbrains.annotations.Nullable;

import software.amazon.awscdk.Environment;
import software.amazon.awscdk.PhysicalName;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.codedeploy.EcsApplication;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentGroup;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentGroupAttributes;
import software.amazon.awscdk.services.codedeploy.IEcsDeploymentGroup;
import software.amazon.awscdk.services.iam.AccountPrincipal;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.constructs.Construct;

public class CrossAccountApplication extends Application {
    
    Role codeDeployActionRole;
    IEcsDeploymentGroup dg;

    String deploymentConfig;
    Environment env;
    EnvType envType;

    public CrossAccountApplication(Construct scope, String appName, String deploymentConfig, EnvType envType, CrossAccountApplicationProps props){
        super(scope, appName, deploymentConfig, envType, props);
        codeDeployActionRole   =   createCodeDeployActionRole(appName, props.getEnvPipeline().getAccount());
        this.deploymentConfig   =   deploymentConfig;
        this.env    =   props.getEnv();
        this.envType=   envType;

        //CDK configures the environment of the deployment group according to the stack where the object is created
        dg  =  EcsDeploymentGroup.fromEcsDeploymentGroupAttributes(
            this, 
            appName+"-ecsdeploymentgroup", 
            EcsDeploymentGroupAttributes.builder()
                .deploymentGroupName( appName+"-"+envType.toString().toLowerCase() )
                .application(EcsApplication.fromEcsApplicationName(
                    this, 
                    appName+"-ecs-deploy-app", 
                    appName+"-"+envType.toString().toLowerCase()))                                            
                .build());       
    }

    private Role createCodeDeployActionRole(final String appName, final String pipelineAccount ){

        return  Role.Builder.create(this, appName+"-codedeploy-role")       //TODO revise this set of roles.
            .assumedBy(new AccountPrincipal( pipelineAccount ))
            .roleName(PhysicalName.GENERATE_IF_NEEDED)
            .description("CodeDeploy Execution Role for "+appName)
            .path("/")
            .managedPolicies(Arrays.asList(
                ManagedPolicy.fromAwsManagedPolicyName("AmazonEC2ContainerRegistryFullAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("AWSCodeDeployRoleForECS"),
                ManagedPolicy.fromAwsManagedPolicyName("CloudWatchLogsFullAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("AmazonECS_FullAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("AWSCodePipelineFullAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("AWSCodeDeployDeployerAccess")
            )).build();  
    }  
    
    public StageConfig getDeploymentConfig(){
        return new StageConfig(deploymentConfig, env, envType, codeDeployActionRole, dg);
    }

    public IEcsDeploymentGroup getDG(){
        return this.dg;
    }

    public Role getCodeDeployActionRole(){
        return this.codeDeployActionRole;
    }

    public static class CrossAccountApplicationProps implements StackProps {

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

        public CrossAccountApplicationProps(Environment envPipeline, Environment env, Map<String,String> tags, Boolean terminationProtection, String stackName){
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

            public CrossAccountApplicationProps build(){
                return new CrossAccountApplicationProps(envPipeline, env, tags, terminationProtection, stackName);
            }
        }
    }      
}