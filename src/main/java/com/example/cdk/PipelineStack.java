package com.example.cdk;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Stage;
import software.amazon.awscdk.StageProps;
import software.amazon.awscdk.pipelines.AddStageOpts;
import software.amazon.awscdk.pipelines.CodeBuildOptions;
import software.amazon.awscdk.pipelines.CodeCommitSourceOptions;
import software.amazon.awscdk.pipelines.CodePipeline;
import software.amazon.awscdk.pipelines.CodePipelineActionFactoryResult;
import software.amazon.awscdk.pipelines.CodePipelineSource;
import software.amazon.awscdk.pipelines.FileSet;
import software.amazon.awscdk.pipelines.ICodePipelineActionFactory;
import software.amazon.awscdk.pipelines.IFileSetProducer;
import software.amazon.awscdk.pipelines.ProduceActionOptions;
import software.amazon.awscdk.pipelines.ShellStep;
import software.amazon.awscdk.pipelines.Step;
import software.amazon.awscdk.services.codebuild.BuildEnvironment;
import software.amazon.awscdk.services.codebuild.ComputeType;
import software.amazon.awscdk.services.codebuild.LinuxBuildImage;
import software.amazon.awscdk.services.codecommit.IRepository;
import software.amazon.awscdk.services.codedeploy.EcsApplication;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentGroup;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentGroupAttributes;
import software.amazon.awscdk.services.codedeploy.IEcsDeploymentGroup;
import software.amazon.awscdk.services.codepipeline.Artifact;
import software.amazon.awscdk.services.codepipeline.IStage;
import software.amazon.awscdk.services.codepipeline.Pipeline;
import software.amazon.awscdk.services.codepipeline.actions.CodeCommitTrigger;
import software.amazon.awscdk.services.codepipeline.actions.CodeDeployEcsContainerImageInput;
import software.amazon.awscdk.services.codepipeline.actions.CodeDeployEcsDeployAction;
import software.amazon.awscdk.services.ecr.assets.DockerImageAsset;
import software.amazon.awscdk.services.iam.ArnPrincipal;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.constructs.Construct;

public class PipelineStack extends Stack {
    
    public PipelineStack(Construct scope, String id, final PipelineStackProps props) throws Exception{

        super(scope, id, props);

        String appName  =   props.getAppName();
        IRepository gitRepo =   props.getGitRepo();

        CodePipelineSource  source  =   CodePipelineSource.codeCommit(
            gitRepo,
            "main",
            CodeCommitSourceOptions
                .builder()
                .trigger(CodeCommitTrigger.POLL)
                .build());

        Role pipelineRole   =   createPipelineRole(appName);
        Role codeDeployRole =   createCodeDeployRole(appName, pipelineRole);
        
        pipelineRole.addToPolicy(
            PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(Arrays.asList("sts:AssumeRole"))
                .resources(Arrays.asList(codeDeployRole.getRoleArn()))
                .build());

        String buildNumber = (String)this.getNode().tryGetContext("buildNumber");
        String seedImageURI = null;
        Boolean ADD_CODE_DEPLOY = Boolean.FALSE;

        Pipeline codePipeline   =   null;

        if( buildNumber != null && !"undefined".equals(buildNumber) && !"1".equals(buildNumber) ){

            ADD_CODE_DEPLOY  =   Boolean.TRUE;
            seedImageURI = (String)this.getNode().tryGetContext("seedImageURI");
            codePipeline = Pipeline.Builder.create(this, "-codepipeline")
                .pipelineName(appName)
                .role(pipelineRole)
                .restartExecutionOnUpdate(Boolean.FALSE)
                .build();

        }else if( buildNumber != null && "1".equals(buildNumber) ){

            DockerImageAsset dockerAsset = DockerImageAsset.Builder
                .create(this, appName+"/v1")
                .directory("./target")
                .build();  
            seedImageURI = dockerAsset.getImageUri();
            codePipeline = Pipeline.Builder.create(this, "-codepipeline")
                .pipelineName(appName)
                .role(pipelineRole)
                .restartExecutionOnUpdate(Boolean.TRUE)
                .build();
        }

        CodePipeline pipeline   =   CodePipeline.Builder.create(this, appName+"-pipeline")
            .codePipeline(codePipeline)
            .selfMutation(Boolean.TRUE)
            .publishAssetsInParallel(Boolean.TRUE)
            .dockerEnabledForSelfMutation(Boolean.TRUE)
            .assetPublishingCodeBuildDefaults(getCodeBuildOptions())
            .synthCodeBuildDefaults(getCodeBuildOptions()) 
            .synth(ShellStep.Builder.create(appName+"-synth")
                .input(source)
                .installCommands(Arrays.asList(
                    "npm install -g aws-cdk"
                    ))
                .commands(Arrays.asList(
                    "mkdir cdk.out",
                    "mvn -B clean package",
                    "echo $CODEBUILD_BUILD_NUMBER",
                    "cdk synth -c appName="+appName+" -c buildNumber=$CODEBUILD_BUILD_NUMBER -c seedImageURI="+seedImageURI))
                    .build())
            .build();

         if( ADD_CODE_DEPLOY ){   

            ShellStep codeBuildPre = ShellStep.Builder.create("ConfigureBlueGreenDeploy")
                .input(pipeline.getCloudAssemblyFileSet())
                .additionalInputs(new HashMap<String,IFileSetProducer>(){{
                    put("../source", source);
                }})
                .primaryOutputDirectory("codedeploy")             
                .commands(configureCodeDeploy(appName))
                .build();
                            
                pipeline.addStage(new DeployECS(
                    this, 
                    "Deploy", 
                    appName, 
                    seedImageURI, 
                    StageProps.builder()
                        .env(props.getEnv())
                        .build()), 
                    AddStageOpts.builder()
                        .pre(Arrays.asList(codeBuildPre))
                        .post(Arrays.asList(new CodeDeployStep("codeDeploy", appName, codeBuildPre.getPrimaryOutput(), codeDeployRole)))
                        .build());
        }

        CfnOutput.Builder.create(this, "PipelineRole")
            .description("Pipeline Role name")
            .value(pipelineRole.getRoleName())
            .build();            

        CfnOutput.Builder.create(this, "CodeDeployRole")
            .description("CodeDeploy Role name")
            .value(codeDeployRole.getRoleName())
            .build();          

        try{
            CfnOutput.Builder.create(this, "PipelineName")
                .description("Pipeline name")
                .value(pipeline.getPipeline()==null ? "n/a" : pipeline.getPipeline().getPipelineName())
                .build();
        }catch(Exception e){
            System.out.println("Not showing output PipelineName because it has not yet been created");
        }
    }
    private class DeployECS extends Stage {

        public DeployECS(Construct scope, String id, String appName, String seedImageURI, StageProps props) throws  Exception{
            super(scope, id);
            new ServiceAssetStack(this, appName+"-svc", appName, StackProps.builder().env(props.getEnv()).build());
            new ECSStack(this, appName+"-ecs", appName, seedImageURI, StackProps.builder().env(props.getEnv()).build());
        }
    }

    private class CodeDeployStep extends Step implements ICodePipelineActionFactory{

        String appName;
        FileSet fileSet;
        Role codeDeployRole;

        public CodeDeployStep(String id, String appName, FileSet fileSet, Role codeDeployRole){
            super(id);
            this.appName    =   appName;
            this.fileSet    =   fileSet;
            this.codeDeployRole =   codeDeployRole;
        }

        @Override
        public @NotNull CodePipelineActionFactoryResult produceAction(@NotNull IStage stage, @NotNull ProduceActionOptions options) {

            Artifact artifact   =   options.getArtifacts().toCodePipeline(fileSet);
            System.out.println("CodeBuildStep::adding action to the stage: "+stage.getStageName()+", Run Order = "+options.getRunOrder());

            IEcsDeploymentGroup dg  =   EcsDeploymentGroup.fromEcsDeploymentGroupAttributes(
                PipelineStack.this, 
                appName+"-ecsdeploymentgroup", 
                EcsDeploymentGroupAttributes.builder()
                    .deploymentGroupName( appName )
                    .application(EcsApplication.fromEcsApplicationName(PipelineStack.this, appName+"-ecs-deploy-app", appName))                                            
                    .build());

            stage.addAction(CodeDeployEcsDeployAction.Builder.create()
                .actionName("Deploy")
                .role(codeDeployRole)
                .appSpecTemplateInput(artifact)
                .taskDefinitionTemplateInput(artifact)
                .runOrder(options.getRunOrder())
                .containerImageInputs(Arrays.asList(CodeDeployEcsContainerImageInput.builder()
                                        .input(artifact)
                                        .taskDefinitionPlaceholder("IMAGE1_NAME")
                                        .build()))
                .deploymentGroup(dg)
                .variablesNamespace("deployment")
                .build());

            return CodePipelineActionFactoryResult.builder().runOrdersConsumed(1).build();
        }
        
    }

    private CodeBuildOptions getCodeBuildOptions(){
        return CodeBuildOptions.builder()
            .buildEnvironment(BuildEnvironment.builder()
                .computeType(ComputeType.MEDIUM)
                .buildImage(LinuxBuildImage.AMAZON_LINUX_2_3)
                .privileged(Boolean.TRUE)//TODO test with priviledge = false
                .build())
            .build();
    }

    private List<String> configureCodeDeploy(String appName){
        return Arrays.asList(
            "mkdir codedeploy",
            "ls -l",
            "find . -type f -exec cat {} \\;",
            "export REPO_NAME=$(cat "+appName+"-svc.assets.json | jq -r '.dockerImages[] | .destinations[] | .repositoryName')",
            "export TAG_NAME=$(cat "+appName+"-svc.assets.json | jq -r '.dockerImages | keys[0]')",
            "echo $REPO_NAME",
            "echo $TAG_NAME",
            "printf '{\"ImageURI\":\"%s\"}' \""+this.getAccount()+".dkr.ecr."+this.getRegion()+".amazonaws.com/$REPO_NAME:$TAG_NAME\" > codedeploy/imageDetail.json",                    
            "sed 's#APPLICATION#"+appName+"#g' ../source/appspec-template.yaml >> codedeploy/appspec.yaml",
            "sed 's#APPLICATION#"+appName+"#g' ../source/taskdef-template.json | sed 's#TASK_EXEC_ROLE#"+"arn:aws:iam::"+this.getAccount()+":role/"+appName+"#g' | sed 's#fargate-task-definition#"+appName+"#g' >> codedeploy/taskdef.json",
            "cat codedeploy/appspec.yaml",
            "cat codedeploy/taskdef.json",
            "cat codedeploy/imageDetail.json"        
        );     
    }

    private Role createPipelineRole(final String appName){

        return Role.Builder.create(this, appName+"pipelineRole")
            .assumedBy(ServicePrincipal.Builder.create("codepipeline.amazonaws.com").build())
            .roleName(appName+"-pipelineRole")
            .build();
    }

    private Role createCodeDeployRole(final String appName, Role codePipelineRole){
        
        Role pipelineRole   =  Role.Builder.create(this, appName+"-codedeploy-role")
            .assumedBy(new ArnPrincipal(codePipelineRole.getRoleArn()))
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

        return pipelineRole;
    }

    public static class PipelineStackProps implements StackProps {


        String appName = null;
        private IRepository gitRepo;     
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


        public PipelineStackProps(String appName, IRepository gitRepo, Environment environment, Map<String,String> tags, Boolean terminationProtection){
            this.appName = appName;
            this.environment = environment;
            this.tags = tags;
            this.terminationProtection = terminationProtection;
            this.gitRepo = gitRepo;
        }

        public static Builder builder(){
            return new Builder();
        }
        static class Builder{


            private String appName;
            private IRepository gitRepo;
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

            public PipelineStackProps build(){
                return new PipelineStackProps(appName, gitRepo, environment, tags, terminationProtection);
            }
        }
    }    
}
