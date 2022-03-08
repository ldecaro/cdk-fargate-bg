package com.example.cdk;

import java.util.Arrays;
import java.util.Map;

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

public class CrossAccountApplicationStack extends ApplicationStack {
    
    Role codeDeployActionRole   =   null;
    IEcsDeploymentGroup dg  =   null;

    public CrossAccountApplicationStack(Construct scope, String id, String appName, ServiceAssetStackProps props){
        super(scope, id, appName, props);
        codeDeployActionRole   =   createCodeDeployActionRole(appName, props.getEnvPipeline().getAccount());
        //CDK configures the environment of the deployment group according to the stack where the object is created
        dg  =  EcsDeploymentGroup.fromEcsDeploymentGroupAttributes(
            this, 
            appName+"-ecsdeploymentgroup", 
            EcsDeploymentGroupAttributes.builder()
                .deploymentGroupName( appName )
                .application(EcsApplication.fromEcsApplicationName(
                    this, 
                    appName+"-ecs-deploy-app", 
                    appName))                                            
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
    
    public IEcsDeploymentGroup getDeploymentGroup(){
        return dg;
    }

    public Role getCodeDeployActionRole(){
        return codeDeployActionRole;
    }
    public static class ServiceAssetStackProps implements StackProps {

        Environment envPipeline     =   null;
        Environment env             =   null;
        Map<String,String> tags     =   null;
        Boolean terminationProtection   =   Boolean.FALSE;
        String stackName    =   null;

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

        public ServiceAssetStackProps(Environment envPipeline, Environment env, Map<String,String> tags, Boolean terminationProtection, String stackName){
            this.envPipeline = envPipeline;
            this.env = env;
            this.tags = tags;
            this.terminationProtection = terminationProtection;
            this.stackName  =   stackName;
        }

        public static Builder builder(){
            return new Builder();
        }
        static class Builder{

            private Environment envPipeline =   null;
            private Environment env =   null;
            private Map<String,String> tags =   null;;
            private Boolean terminationProtection = Boolean.FALSE;
            private String stackName    =   null;

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

            public ServiceAssetStackProps build(){
                return new ServiceAssetStackProps(envPipeline, env, tags, terminationProtection, stackName);
            }
        }
    }      
}
