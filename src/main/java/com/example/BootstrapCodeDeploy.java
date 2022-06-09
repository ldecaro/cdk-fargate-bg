package com.example;

import java.util.Arrays;
import java.util.HashMap;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.iam.AccountPrincipal;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Policy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.constructs.Construct;

public class BootstrapCodeDeploy extends Stack {
    
    public BootstrapCodeDeploy(Construct scope, StackProps props){

        super(scope, "aws-code-deploy-bootstrap", props);

        Role crossAccountRole = Role.Builder.create(this, "AWSCodeDeployRoleForBlueGreen")
            .assumedBy(new AccountPrincipal( Util.getTrustedAccount() == null ? props.getEnv().getAccount() : Util.getTrustedAccount() ))
            .roleName("AWSCodeDeployRoleForBlueGreen")
            .description("CodeDeploy Execution Role for Blue Green Deploy")
            .path("/")
            .managedPolicies(Arrays.asList(
                ManagedPolicy.fromAwsManagedPolicyName("AmazonEC2ContainerRegistryFullAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("AmazonECS_FullAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("AWSCodePipelineFullAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("CloudWatchLogsFullAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("AWSCodeDeployRoleForECS"),
                ManagedPolicy.fromAwsManagedPolicyName("AWSCodeDeployDeployerAccess")                
            )).build();    


        //If cross account we need to explicitly add the statement allowing role to use 
        //KMS in the toolchain account
        // https://docs.aws.amazon.com/kms/latest/developerguide/key-policy-modifying-external-accounts.html
        if( isCrossAccount() ){

            crossAccountRole.attachInlinePolicy(
                
                    Policy.Builder.create(this, "kms-codepipeline-cross-account")
                        .policyName("KMSArfifactAWSCodePipeline")
                        .statements(Arrays.asList(
                            PolicyStatement.Builder.create()
                            .effect(Effect.ALLOW)
                            .actions(Arrays.asList("kms:Decrypt", "kms:DescribeKey"))
                            .resources( Arrays.asList("arn:aws:kms:*:"+Util.getTrustedAccount()+":key/*") )
                            .conditions(new HashMap<String,Object>(){{
                                put("ForAnyValue:StringLike", new HashMap<String, Object>(){{
                                    put("kms:ResourceAliases", Arrays.asList("alias/codepipeline*")); 
                                }});
                            }})
                            .build()
                        )).build()
            );
        }
        CfnOutput.Builder.create(this, "CrossAccountCodeDeployRole" )
        .description("Cross Account CodeDeploy role created for account: "+props.getEnv().getAccount()+"/"+props.getEnv().getRegion())
        .value(crossAccountRole.getRoleArn());
    }

    private Boolean isCrossAccount(){
        return Util.getTrustedAccount()== null? Boolean.FALSE : Boolean.TRUE;
    }
}