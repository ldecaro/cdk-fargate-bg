package com.example.api.infrastructure;

import java.util.Arrays;

import com.example.DeploymentConfig;
import com.example.DeploymentConfig.EnvType;

import software.amazon.awscdk.Environment;
import software.amazon.awscdk.PhysicalName;
import software.amazon.awscdk.services.codedeploy.EcsApplication;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentGroup;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentGroupAttributes;
import software.amazon.awscdk.services.codedeploy.IEcsDeploymentGroup;
import software.amazon.awscdk.services.iam.AccountPrincipal;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.constructs.Construct;

public class CrossAccountService extends LocalService {
    
    Role codeDeployActionRole;
    IEcsDeploymentGroup dg;

    String deploymentConfig;
    Environment env;
    EnvType envType;

    public CrossAccountService(Construct scope, String appName, String deploymentConfig, EnvType envType, CrossAccountServiceProps props){
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
    
    public DeploymentConfig getDeploymentConfig(){
        return new DeploymentConfig(deploymentConfig, env, envType, codeDeployActionRole, dg);
    }

    public IEcsDeploymentGroup getDG(){
        return this.dg;
    }

    public Role getCodeDeployActionRole(){
        return this.codeDeployActionRole;
    }
}