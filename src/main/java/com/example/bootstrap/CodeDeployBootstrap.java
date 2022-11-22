package com.example.bootstrap;

import java.util.Arrays;
import java.util.HashMap;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnParameter;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.iam.AccountPrincipal;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Policy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.constructs.Construct;

public class CodeDeployBootstrap extends Stack {
    
    public CodeDeployBootstrap(Construct scope, String id, StackProps props){

        super(scope, id, props);
            
        CfnParameter codeDeployToolchainAccount = CfnParameter.Builder.create(this, "toolchainAccount")
            .type("String")
            .description("The account number where the toolchain is deployed")
            .build();  

        String toolchainAccount = codeDeployToolchainAccount.getValueAsString();

        Role crossAccountRole = Role.Builder.create(this, CodeDeployBootstrap.getRoleName())
            .assumedBy(new AccountPrincipal( toolchainAccount ))
            .roleName(CodeDeployBootstrap.getRoleName())
            .description("CodeDeploy Execution Role for Blue Green Deploy")
            .path("/")
            .managedPolicies(Arrays.asList(
                ManagedPolicy.fromAwsManagedPolicyName("AmazonEC2ContainerRegistryFullAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("AmazonECS_FullAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("AWSCodePipeline_FullAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("CloudWatchLogsFullAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("AWSCodeDeployRoleForECS"),
                ManagedPolicy.fromAwsManagedPolicyName("AWSCodeDeployDeployerAccess")                
            )).build();

        Policy policy = Policy.Builder.create(this, "kms-codepipeline-cross-region-account")
            .policyName("KMSArfifactAWSCodePipeline")
            .statements(Arrays.asList(
                PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(Arrays.asList("kms:Decrypt", "kms:DescribeKey"))
                .resources( Arrays.asList("arn:aws:kms:*:"+toolchainAccount+":key/*") )
                .conditions(new HashMap<String,Object>(){{
                    put("ForAnyValue:StringLike", new HashMap<String, Object>(){{
                        put("kms:ResourceAliases", Arrays.asList("alias/codepipeline*", "alias/*encryptionalias*")); 
                    }});
                }})
                .build()
            )).build();

        crossAccountRole.attachInlinePolicy( policy );

        CfnOutput.Builder.create(this, "CrossAccountCodeDeployRole" )
        .description("Cross Account CodeDeploy role created for account: "+props.getEnv().getAccount()+"/"+props.getEnv().getRegion())
        .value(crossAccountRole.getRoleArn());
    }

    public static String getRoleName(){
        return "AWSCodeDeployRoleForBlueGreen";
    }
}