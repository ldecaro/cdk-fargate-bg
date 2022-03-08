package com.example.cdk;

import java.util.Arrays;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import software.amazon.awscdk.Environment;
import software.amazon.awscdk.PhysicalName;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.codedeploy.EcsApplication;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentGroup;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentGroupAttributes;
import software.amazon.awscdk.services.codedeploy.IEcsDeploymentGroup;
import software.amazon.awscdk.services.ecr.assets.DockerImageAsset;
import software.amazon.awscdk.services.iam.AccountPrincipal;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.constructs.Construct;

public class ServiceAssetStack extends Stack {
    
    private Role codeDeployActionRole   =   null;
    private IEcsDeploymentGroup dg  =   null;

    public ServiceAssetStack(Construct scope, String id, String appName, final ServiceAssetStackProps props) {

        super(scope, id, props);

        codeDeployActionRole   =   createCodeDeployActionRole(appName, props.getEnvPipeline().getAccount());
        //CDK configures the deployment group using the environment (account,region) from the stack that creates the object
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
        //after stacks get created during boot time, DockerImageAsset will run during synth, from inside the pipeline
        DockerImageAsset.Builder
        .create(this, appName+"-container")
        .directory("./target")
        .build();

        //if this is running in CodeBuild we can update the role that is in the target-account
        // if(! props.isCreating() ){

        //     //if this is a cross-account scenario we need to add extra policies into the role of the codedeploy action
        //     if(! props.getEnvPipeline().getAccount().equals(props.getEnv().getAccount())){

        //         //role used by the codedeploy action must be created before we configure key policy in KMS.
        //         //after that, we update this role to add grants to the KMS and S3 bucket where artifacts are stored.
        //         codeDeployActionRole.addToPolicy(PolicyStatement.Builder.create()
        //             .effect(Effect.ALLOW)
        //             .actions(Arrays.asList("s3:Get*"))
        //             .resources(Arrays.asList( props.getArtifactS3Arn()+"/*" ))
        //             .build());

        //         codeDeployActionRole.addToPolicy(PolicyStatement.Builder.create()
        //             .effect(Effect.ALLOW)
        //             .actions(Arrays.asList("s3:ListBucket"))
        //             .resources(Arrays.asList( props.getArtifactS3Arn() ))
        //             .build());

        //         codeDeployActionRole.addToPolicy(PolicyStatement.Builder.create()
        //             .effect(Effect.ALLOW)
        //             .actions(Arrays.asList(
        //                     "kms:DescribeKey", 
        //                     "kms:GenerateDataKey", 
        //                     "kms:Encrypt", 
        //                     "kms:ReEncrypt", 
        //                     "kms:Decrypt"))
        //             .resources(Arrays.asList( props.getArtifactKMSArn() )) //check if this KMS ID is the same from CODEBUILD_KMS_KEY_ID
        //             .build());    
        //     }                                   
        // }
    }

    private Role createCodeDeployActionRole(final String appName, final String pipelineAccount ){

        return  Role.Builder.create(this, appName+"-codedeploy-role")       //TODO revise this set of roles. // if this is executing from the same account let's make sure that the principal who can assume it is the pipeline (maybe we could use the role name with a wildcard? or reference the service?)         
            .assumedBy(new AccountPrincipal( pipelineAccount ))
            // .assumedBy( new ArnPrincipal( "arn:aws:iam::"+pipelineAccount+":role/"+appName+"-pipelineRole") )
            .roleName(PhysicalName.GENERATE_IF_NEEDED)
            .description("CodeDeploy Execution Role for "+appName)
            .path("/")
            .managedPolicies(Arrays.asList(
                ManagedPolicy.fromAwsManagedPolicyName("AmazonEC2ContainerRegistryFullAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("AWSCodeCommitPowerUser"),
                ManagedPolicy.fromAwsManagedPolicyName("AmazonECS_FullAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("AWSCodePipelineFullAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("CloudWatchLogsFullAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("AWSCodeDeployRoleForECS") 
            )).build();  
    }

    public static class ServiceAssetStackProps implements StackProps {

        String appName              =   null;
        Environment envPipeline     =   null;
        Environment env             =   null;
        Map<String,String> tags     =   null;
        Boolean terminationProtection   =   Boolean.FALSE;
        String artifactKMSArn       =   null;
        String artifactS3Arn        =   null;
        Boolean isCreating  =   Boolean.FALSE;
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
            return "ApplicationStack. Application: "+getAppName();
        }

        public String getAppName(){
            return appName;
        }

        public Environment getEnvPipeline(){
            return envPipeline;
        }

        public Environment getEnv() {
            return env;
        }

        public String getArtifactS3Arn(){
            return this.artifactS3Arn;
        }

        public String getArtifactKMSArn(){
            return this.artifactKMSArn;
        }        

        public Boolean isCreating(){
            return isCreating;
        }
        @Override
        public String getStackName(){
            return stackName;
        }

        public ServiceAssetStackProps(String appName,Environment envPipeline, Environment env, Map<String,String> tags, Boolean terminationProtection, String artifactS3Arn, String artifactKMSArn, Boolean IS_CREATING, String stackName){
            this.appName = appName;
            this.envPipeline = envPipeline;
            this.env = env;
            this.tags = tags;
            this.terminationProtection = terminationProtection;
            this.artifactS3Arn = artifactS3Arn;
            this.artifactKMSArn = artifactKMSArn;
            this.isCreating = IS_CREATING;
            this.stackName  =   stackName;
        }

        public static Builder builder(){
            return new Builder();
        }
        static class Builder{

            private String appName  =   null;
            private Environment envPipeline =   null;
            private Environment env =   null;
            private Map<String,String> tags =   null;;
            private Boolean terminationProtection = Boolean.FALSE;
            private String artifactS3Arn    =   null;
            private String artifactKMSArn   =   null;
            private Boolean isCreating =   null;
            private String stackName    =   null;

            public Builder appName(String appName){
                this.appName = appName;
                return this;
            }

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

            public Builder artifactS3Arn(String artifactS3Arn){
                this.artifactS3Arn = artifactS3Arn;
                return this;
            }

            public Builder artifactKMSArn(String artifactKMSArn){
                this.artifactKMSArn = artifactKMSArn;
                return this;
            }

            public Builder isCreating(final Boolean IS_CREATING){//TODO we don't need this method anymore..
                this.isCreating = IS_CREATING;
                return this;
            }

            public Builder stackName(final String stackName){
                this.stackName = stackName;
                return this;
            }

            public ServiceAssetStackProps build(){
                return new ServiceAssetStackProps(appName, envPipeline, env, tags, terminationProtection, artifactS3Arn, artifactKMSArn, isCreating, stackName);
            }
        }
    }   
    
    public Role getCodeDeployActionRole(){//TODO fix the name of this method here and in the pipeline stack. Remove the Arn suffix.
        return  this.codeDeployActionRole;//.getRoleArn();
    }

    public IEcsDeploymentGroup getDG(){
        return this.dg;
    }
}
