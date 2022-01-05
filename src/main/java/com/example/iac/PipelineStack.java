package com.example.iac;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import software.amazon.awscdk.CustomResource;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.customresources.Provider;
import software.amazon.awscdk.services.codebuild.BuildEnvironment;
import software.amazon.awscdk.services.codebuild.ComputeType;
import software.amazon.awscdk.services.codebuild.IProject;
import software.amazon.awscdk.services.codebuild.LinuxBuildImage;
import software.amazon.awscdk.services.codebuild.PipelineProject;
import software.amazon.awscdk.services.codecommit.IRepository;
import software.amazon.awscdk.services.codepipeline.Artifact;
import software.amazon.awscdk.services.codepipeline.IStage;
import software.amazon.awscdk.services.codepipeline.StageOptions;
import software.amazon.awscdk.services.codepipeline.actions.CodeBuildAction;
import software.amazon.awscdk.services.codepipeline.actions.CodeBuildActionType;
import software.amazon.awscdk.services.codepipeline.actions.CodeCommitSourceAction;
import software.amazon.awscdk.services.codepipeline.actions.CodeCommitTrigger;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.PolicyDocument;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.LayerVersion;
import software.amazon.awscdk.services.lambda.SingletonFunction;
import software.amazon.awscdk.services.lambda.Tracing;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketEncryption;
import software.constructs.Construct;

public class PipelineStack extends Stack {

    private software.amazon.awscdk.services.codepipeline.Pipeline pipeline;
    private Artifact build;
    private Artifact source;
    private Role deployRole;
    
    public PipelineStack(Construct scope, String id, final PipelineStackProps props) throws Exception{

        super(scope, id, props);

        final String listenerBlueArn = props.getListenerBlueArn();
        final String listenerGreenArn   =   props.getListenerGreenArn();
        final String tgBlueName =   props.getTgBlueName();
        final String tgGreenName=   props.getTgGreenName();

        String appName  =   props.getAppName();
        IRepository gitRepo =   props.getGitRepo();

        //creating the ECR Repository
        software.amazon.awscdk.services.ecr.Repository repo =   software.amazon.awscdk.services.ecr.Repository.Builder.create(this, appName+"-ecr").repositoryName(appName+"").build();
        repo.applyRemovalPolicy(RemovalPolicy.DESTROY);

        //pipeline bucket
        Bucket pipelineBucket = Bucket.Builder.create(this, "pipeline-staging-"+appName).bucketName("codepipeline-staging-"+appName).encryption(BucketEncryption.S3_MANAGED).build();
        pipelineBucket.applyRemovalPolicy(RemovalPolicy.DESTROY);

        //pipeline
        Role deployRole         =   createCodeDeployExecutionRole(appName, props);
        Role customLambdaRole   =   createCustomLambdaRole(appName, props, deployRole);    

        
        Role pipelineRole   =   createPipelineExecutionRole(appName, props);

        software.amazon.awscdk.services.codepipeline.Pipeline pipe  =   software.amazon.awscdk.services.codepipeline.Pipeline
                                                                            .Builder.create(this, appName+"-pipeline")
                                                                            .pipelineName(appName)
                                                                            .role( pipelineRole )
                                                                            .artifactBucket(pipelineBucket)
                                                                            .build();
        // gitRepo.grantPull( pipelineRole );
        pipelineRole.addToPolicy(PolicyStatement.Builder.create()
                                    .actions(Arrays.asList("codecommit:GitPull"))
                                    .effect(Effect.ALLOW)
                                    .resources(Arrays.asList(gitRepo.getRepositoryArn()))
                                    .build());
        IStage source = pipe.addStage(StageOptions.builder().stageName("Source").build());
            
        //src
        Artifact sourceArtifact = new Artifact("SourceArtifact");
        Artifact buildArtifact  =   new Artifact("BuildArtifact");

        source.addAction(CodeCommitSourceAction.Builder.create()
                        .actionName("Source")
                        .branch("main")
                        .output(sourceArtifact)
                        .trigger(CodeCommitTrigger.POLL)
                        .repository(gitRepo)
                        .build());
        //build

        Role buildRole    =   createCodeBuildExecutionRole(appName);

        IStage build = pipe.addStage(StageOptions.builder().stageName("Build").build() );
        build.addAction(CodeBuildAction.Builder
                        .create()
                        .input(sourceArtifact)
                        .actionName("Build")
                        .outputs(Arrays.asList(buildArtifact))
                        .type( CodeBuildActionType.BUILD )
                        .variablesNamespace("BuildVariables")
                        .project( createBuildProject( appName, buildRole ) )
                        .build());

        //deploy

        // create CustomLambda to execute CLI command        
        final Map<String,String> lambdaEnv	=	new HashMap<>();
        lambdaEnv.put("appName", appName);
        lambdaEnv.put("accountNumber", props.getEnv().getAccount());
        lambdaEnv.put("roleName", deployRole.getRoleArn());
        lambdaEnv.put("greenListenerArn", listenerGreenArn);
        lambdaEnv.put("blueListenerArn", listenerBlueArn);
        lambdaEnv.put("tgNameBlue", tgBlueName);
        lambdaEnv.put("tgNameGreen", tgGreenName);
        
        LayerVersion cliLayer = LayerVersion.Builder.create(this, appName+"cli-layer")
                                    .code(Code.fromAsset("./lambda/awscli-lambda-layer.zip"))
                                    .description("This is a lambda layer that will add AWSCLI capability into your python lambda")
                                    .compatibleRuntimes(Arrays.asList(software.amazon.awscdk.services.lambda.Runtime.PYTHON_3_9))
                                    .build();

        SingletonFunction customResource = SingletonFunction.Builder.create(this, appName+"-codedeploy-blue-green-lambda")
                                        .uuid(UUID.randomUUID().toString())
                                        .functionName("codedeploy-blue-green-lambda")
                                        .runtime(software.amazon.awscdk.services.lambda.Runtime.PYTHON_3_9)
                                        .timeout(Duration.seconds(870))
                                        .memorySize(512)
                                        .tracing(Tracing.ACTIVE)
                                        .layers(Arrays.asList(cliLayer))
                                        .code(Code.fromAsset("./lambda/code-deploy-blue-green.zip"))
                                        .handler("lambda_function.lambda_handler")
                                        .environment(lambdaEnv)
                                        .logRetention(RetentionDays.ONE_MONTH)
                                        .role(customLambdaRole)
                                        .build();

        Provider provider   =   Provider.Builder.create(this, appName+"-codedeploy-lambda-provider")
                                        .onEventHandler(customResource)
                                        .build();
        CustomResource.Builder.create(this, appName+"-custom-resource")
                                        .serviceToken(provider.getServiceToken())
                                        .properties(lambdaEnv)
                                        .build();

        this.pipeline = pipe;
        this.deployRole = pipelineRole;
        this.build = buildArtifact;
        this.source = sourceArtifact;
    }

    private IProject createBuildProject(String appName, Role buildRole){
        return PipelineProject.Builder
                    .create(this, appName+"build")
                    .projectName(appName)
                    .description("Build project for "+appName+" using Java8, Maven and Docker")
                    .timeout(Duration.minutes(5))
                    .queuedTimeout(Duration.minutes(10))
                    .role(buildRole)
                    .environment(BuildEnvironment.builder()
                                    .computeType(ComputeType.SMALL)
                                    .buildImage(LinuxBuildImage.AMAZON_LINUX_2_3)
                                    .privileged(Boolean.TRUE)
                                    .build() )
                    .build();
    }

    private Role createPipelineExecutionRole(final String appName, StackProps props){
        
        return Role.Builder.create(this, appName+"-pipeline-role")
                            .roleName(appName+"-pipeline")
                            .assumedBy(ServicePrincipal.Builder.create("codepipeline.amazonaws.com").build())
                            .description("CodePipeline Execution Role for "+appName)
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

    private Role createCodeBuildExecutionRole(final String appName){

        return Role.Builder.create(this, appName+"-codebuild-role")
                            .roleName(appName+"-codebuild")
                            .assumedBy(ServicePrincipal.Builder.create("codebuild.amazonaws.com").build())
                            .description("CodeBuild Execution Role for "+appName)
                            .path("/")
                            .managedPolicies(Arrays.asList(
                                ManagedPolicy.fromAwsManagedPolicyName("AWSCodeBuildDeveloperAccess"),
                                ManagedPolicy.fromAwsManagedPolicyName("AmazonEC2ContainerRegistryFullAccess"),
                                ManagedPolicy.fromAwsManagedPolicyName("AmazonECS_FullAccess"),
                                ManagedPolicy.fromAwsManagedPolicyName("CloudWatchLogsFullAccess")
                            ))
                            .build();
    }

    private Role createCustomLambdaRole(final String appName, StackProps props, Role codeDeployRole){

        return Role.Builder.create(this, appName+"-customer-lambdarole")
                            .inlinePolicies(new HashMap<String, PolicyDocument>(){
                                private static final long serialVersionUID = 6728018370248392366L;
                                {
                                    put(appName+"Policy", 		
                                            PolicyDocument.Builder.create().statements(				
                                            Arrays.asList(				
                                                    PolicyStatement.Builder.create()
                                                        .actions(Arrays.asList("iam:PassRole"))
                                                        .effect(Effect.ALLOW)
                                                        .sid("CodeDeployBlueGreenPassRole")
                                                        .resources(Arrays.asList("arn:aws:iam::"+props.getEnv().getAccount()+":role/"+codeDeployRole.getRoleName()))
                                                        .build())).build());
                                }
                            })
                            .managedPolicies(Arrays.asList(                                
                                ManagedPolicy.fromAwsManagedPolicyName("AWSCodeDeployFullAccess"),
                                ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole")
                            ))
                            .roleName(appName+"-custom-lambda-role")
                            .assumedBy(ServicePrincipal.Builder.create("lambda.amazonaws.com").build())
                            .description("Lambda Execution Role for "+appName+" to create a BlueGreen deployment")
                            .path("/")
                            .build();
    }

    private Role createCodeDeployExecutionRole(final String appName, StackProps props){

        return Role.Builder.create(this, appName+"-codedeploy-role")

                            .roleName(appName+"-codedeploy")
                            .assumedBy(ServicePrincipal.Builder.create("codedeploy.amazonaws.com").build())
                            // .assumedBy(ServicePrincipal.Builder.create("codepipeline.amazonaws.com").build())
                            .description("CodeBuild Execution Role for "+appName)
                            .path("/")
                            .managedPolicies(Arrays.asList(
                                ManagedPolicy.fromAwsManagedPolicyName("AWSCodeBuildDeveloperAccess"),
                                ManagedPolicy.fromAwsManagedPolicyName("AmazonEC2ContainerRegistryFullAccess"),
                                ManagedPolicy.fromAwsManagedPolicyName("AmazonECS_FullAccess"),
                                ManagedPolicy.fromAwsManagedPolicyName("CloudWatchLogsFullAccess"),
                                ManagedPolicy.fromAwsManagedPolicyName("AWSCodeDeployRoleForECS"),
                                ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole")
                            ))
                            .build();
    }    

    public software.amazon.awscdk.services.codepipeline.Pipeline getPipeline() {
        return pipeline;
    }

    public Artifact getBuild() {
        return build;
    }

    public Role getDeployRole() {
        return deployRole;
    }

    public Artifact getSource(){
        return this.source;
    }

    public static class PipelineStackProps implements StackProps {


        String appName = null;
        private IRepository gitRepo;
        private String listenerBlueArn;
        private String listenerGreenArn;
        private String tgBlueName;
        private String tgGreenName;        
        Environment environment = null;
        Map<String,String> tags;
        Boolean terminationProtection;

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
            return "Control Plane Stack of the app "+getAppName();
        }

        public String getAppName(){
            return this.appName;
        }

        public Environment getEnv(){
            return environment;
        }

        public IRepository getGitRepo() {
            return gitRepo;
        }

        public String getListenerBlueArn() {
            return listenerBlueArn;
        }

        public String getListenerGreenArn() {
            return listenerGreenArn;
        }

        public String getTgBlueName() {
            return tgBlueName;
        }

        public String getTgGreenName() {
            return tgGreenName;
        }

        public PipelineStackProps(String appName, IRepository gitRepo, String listenerBlueArn, String listenerGreenArn, String tgBlueName, String tgGreenName, Environment environment, Map<String,String> tags, Boolean terminationProtection){
            this.appName = appName;
            this.environment = environment;
            this.tags = tags;
            this.terminationProtection = terminationProtection;
            this.gitRepo = gitRepo;
            this.listenerBlueArn = listenerBlueArn;
            this.listenerGreenArn = listenerGreenArn;
            this.tgBlueName = tgBlueName;
            this.tgGreenName = tgGreenName;
        }

        public static Builder builder(){
            return new Builder();
        }
        static class Builder{


            private String appName;
            private IRepository gitRepo;
            private String listenerBlueArn;
            private String listenerGreenArn;
            private String tgBlueName;
            private String tgGreenName;

            private Environment environment;
            private Map<String,String> tags;
            private Boolean terminationProtection = Boolean.FALSE;

            public Builder appName(String appName){
                this.appName = appName;
                return this;
            }

            public Builder env(Environment environment){
                this.environment = environment;
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

            public Builder gitRepo(IRepository gitRepo){
                this.gitRepo = gitRepo;
                return this;
            }

            public Builder listenerBlueArn(String listenerBlueArn){
                this.listenerBlueArn = listenerBlueArn;
                return this;
            }

            public Builder listenerGreenArn(String listenerGreenArn){
                this.listenerGreenArn = listenerGreenArn;
                return this;
            }

            public Builder tgBlueName(String tgBlueName){
                this.tgBlueName = tgBlueName;
                return this;
            }

            public Builder tgGreenName(String tgGreenName){
                this.tgGreenName = tgGreenName;
                return this;
            }

            public PipelineStackProps build(){
                return new PipelineStackProps(appName, gitRepo, listenerBlueArn, listenerGreenArn, tgBlueName, tgGreenName, environment, tags, terminationProtection);
            }
        }
    }    
}
