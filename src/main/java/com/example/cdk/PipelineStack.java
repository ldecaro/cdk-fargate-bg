package com.example.cdk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.cdk.ECSStack.ECSStackProps;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Stage;
import software.amazon.awscdk.StageProps;
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
import software.amazon.awscdk.pipelines.StageDeployment;
import software.amazon.awscdk.pipelines.Step;
import software.amazon.awscdk.services.codebuild.BuildEnvironment;
import software.amazon.awscdk.services.codebuild.BuildEnvironmentVariable;
import software.amazon.awscdk.services.codebuild.BuildEnvironmentVariableType;
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

        Pipeline codePipeline   =   Pipeline.Builder.create(this, "-codepipeline")
            .role(pipelineRole)
            .crossAccountKeys(Boolean.TRUE)//tirar no pipeline e verificar se a bucket policy do s3 e da kms que encripta o s3 continuam lá. checar o change set no CloudFormation.
            .restartExecutionOnUpdate(Boolean.TRUE)
            .build();

        CodePipeline pipeline   =   CodePipeline.Builder.create(this, appName+"-pipeline")
            .codePipeline(codePipeline)
            .selfMutation(Boolean.TRUE)
            .publishAssetsInParallel(Boolean.FALSE)
            .dockerEnabledForSelfMutation(Boolean.TRUE)
            .synthCodeBuildDefaults(getCodeBuildOptions(appName, props.getDeploymentConfigs()))
            .selfMutationCodeBuildDefaults(getCodeBuildOptions(appName, props.getDeploymentConfigs()))
            .synth(ShellStep.Builder.create(appName+"-synth")
                .input(source)
                .installCommands(Arrays.asList(
                    "npm install -g aws-cdk"
                    ))
                .commands(Arrays.asList(
                    "mkdir cdk.out",
                    "mvn -B clean package",
                    "cd target && ls -d  */ | xargs rm -rf && ls -lah && cd ..", //clean up target folder
                    "cdk synth -c appName=$APP_NAME"))
                .build())
            .build();

        //processing list of deploymentConfigs, one per stage: alpha, beta, gamma
        processDeploymentConfig(props, pipelineRole);

        String [] stageDescription = new String[]{"Alpha", "Beta", "Gamma", "Delta"};
        for(int stageNumber=0; stageNumber<props.getDeploymentConfigs().length; stageNumber++){
            
            DeploymentConfig deployConfig =   props.getDeploymentConfigs()[stageNumber];
            
            ShellStep codeBuildPre = ShellStep.Builder.create("ConfigureBlueGreenDeploy")
                .input(pipeline.getCloudAssemblyFileSet())
                .additionalInputs(new HashMap<String,IFileSetProducer>(){{
                    put("../source", source);
                }})
                .primaryOutputDirectory("codedeploy")             
                .commands(configureCodeDeploy(appName, deployConfig.getEnv(), (stageNumber+1) ))
                .build();    

            DeployECS deploy = new DeployECS(this, 
                "Deploy-"+stageDescription[stageNumber], 
                appName,
                deployConfig.getDeployConfig(),
                stageDescription[stageNumber].toLowerCase(),
                StageProps.builder()
                    .env(deployConfig.getEnv())
                    .build());

            StageDeployment stage = pipeline.addStage(deploy);
            stage.addPre(codeBuildPre);
            stage.addPost(new CodeDeployStep(
                "codeDeploy"+stageDescription[stageNumber], 
                stageNumber+1,
                codeBuildPre.getPrimaryOutput(), 
                deployConfig.getCodeDeployRole(),
                deployConfig.getEcsDeploymentGroup()));
        }

        CfnOutput.Builder.create(this, "PipelineRole")
            .description("Pipeline Role name")
            .value(pipelineRole.getRoleName())
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
    protected class DeployECS extends Stage {

        public DeployECS(Construct scope, String id, String appName, String deployConfig, String stageDescription, StageProps props) throws  Exception{

            super(scope, id);

            new ECSStack(this, 
                appName+"-ecs-"+stageDescription, 
                ECSStackProps.builder()
                    .appName(appName)
                    .deploymentConfig(deployConfig)
                    .stackName(appName+"-ecs-"+stageDescription)
                    .env(props.getEnv())
                    .build());
        }
    }

    // CodeDeploy action executed after the ECSStack. It will control all deployments after ECSStack is created.
    private class CodeDeployStep extends Step implements ICodePipelineActionFactory{

        FileSet fileSet;
        Role codeDeployRole;
        IEcsDeploymentGroup dg;
        Integer stageNumber = 0;

        public CodeDeployStep(String id, Integer stageNumber, FileSet fileSet, Role codeDeployRole, IEcsDeploymentGroup dg){
            super(id);
            this.fileSet    =   fileSet;
            this.codeDeployRole =   codeDeployRole;
            this.dg    =   dg;
            this.stageNumber = stageNumber;
        }

        @Override
        public @NotNull CodePipelineActionFactoryResult produceAction(@NotNull IStage stage, @NotNull ProduceActionOptions options) {

            Artifact artifact   =   options.getArtifacts().toCodePipeline(fileSet);           

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
                .deploymentGroup( dg )
                .variablesNamespace("deployment"+stageNumber)
                .build());

            return CodePipelineActionFactoryResult.builder().runOrdersConsumed(1).build();
        }
        
    }

    private CodeBuildOptions getCodeBuildOptions(String appName, DeploymentConfig[] deploymentConfigs ){

        List<PolicyStatement> policies = new ArrayList<>();
        policies.add(PolicyStatement.Builder.create()
            .effect(Effect.ALLOW)
            .actions(Arrays.asList("sts:AssumeRole")) 
            .resources(Arrays.asList("arn:aws:iam::*:role/cdk-*"))
            .build());

        for(DeploymentConfig deployConfig: deploymentConfigs){

            if( deployConfig.getCodeDeployRole()!=null){
                policies.add(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(Arrays.asList("sts:AssumeRole"))
                .resources(Arrays.asList( deployConfig.getCodeDeployRole().getRoleArn()) )
                .build());
            }
        }

        return CodeBuildOptions.builder()
            .rolePolicy(policies)
            .buildEnvironment(BuildEnvironment.builder()
                .environmentVariables(new HashMap<String,BuildEnvironmentVariable>(){{
                    put("APP_NAME", BuildEnvironmentVariable.builder()
                        .type(BuildEnvironmentVariableType.PLAINTEXT)
                        .value(appName == null ? "" : appName)
                        .build());
                }})
                .computeType(ComputeType.MEDIUM)
                .buildImage(LinuxBuildImage.AMAZON_LINUX_2_3)
                .privileged(Boolean.TRUE)//TODO test with priviledge = false
                .build())
            .build();

    }

    /**
     * Configures appspec.yaml, taskdef.json and imageDetails.json using information coming from the ApplicationStack/DockerImageAsset (.assets)
     * @param appName
     * @param targetEnv
     * @param stageNumber
     * @return
     */
    private List<String> configureCodeDeploy(String appName, Environment targetEnv, Integer stageNumber){
        return Arrays.asList(
            "mkdir codedeploy",
            "ls -l",
            // "find . -type f -exec cat {} \\;",
            "cat *.assets.json",
            "export REPO_NAME=$(cat "+appName+"-svc-"+stageNumber+".assets.json | jq -r '.dockerImages[] | .destinations[] | .repositoryName')",
            "export TAG_NAME=$(cat "+appName+"-svc-"+stageNumber+".assets.json | jq -r '.dockerImages | keys[0]')",
            "echo $REPO_NAME",
            "echo $TAG_NAME",
            "printf '{\"ImageURI\":\"%s\"}' \""+targetEnv.getAccount()+".dkr.ecr."+targetEnv.getRegion()+".amazonaws.com/$REPO_NAME:$TAG_NAME\" > codedeploy/imageDetail.json",                    
            "sed 's#APPLICATION#"+appName+"#g' ../source/blue-green/template-appspec.yaml >> codedeploy/appspec.yaml",
            "sed 's#APPLICATION#"+appName+"#g' ../source/blue-green/template-taskdef.json | sed 's#TASK_EXEC_ROLE#"+"arn:aws:iam::"+targetEnv.getAccount()+":role/"+appName+"#g' | sed 's#fargate-task-definition#"+appName+"#g' >> codedeploy/taskdef.json",
            "cat codedeploy/appspec.yaml",
            "cat codedeploy/taskdef.json",
            "cat codedeploy/imageDetail.json"        
        );     
    }

    private Role createPipelineRole(final String appName){

        return Role.Builder.create(this, appName+"-pipelineRole")
            .assumedBy(ServicePrincipal.Builder.create("codepipeline.amazonaws.com").build())
            .roleName(appName+"-pipelineRole")
            .build();
    }

    private void processDeploymentConfig(final PipelineStackProps props, final Role codePipelineRole){
        
        IEcsDeploymentGroup dg    = null;
        Role codeDeployRole =   null;

        //if this is a cross-account scenario...
        for(DeploymentConfig deployConfig: props.getDeploymentConfigs()){

            if( deployConfig.getCodeDeployRole()!=null ){

                codePipelineRole.addToPolicy(
                PolicyStatement.Builder.create()
                    .effect(Effect.ALLOW)
                    .actions(Arrays.asList("sts:AssumeRole"))
                    .resources(Arrays.asList( deployConfig.getCodeDeployRole().getRoleArn()))
                    .build());
            }else{

                if(codeDeployRole == null){

                    String appName  =   props.getAppName();
                    codeDeployRole   =  Role.Builder.create(this, appName+"-codedeploy-role")
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

                    dg  =   EcsDeploymentGroup.fromEcsDeploymentGroupAttributes(
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
                deployConfig.setCodeDeployRole(codeDeployRole);
                deployConfig.setDeploymentGroup(dg);
            }
        }
    }    

    protected static class PipelineStackProps implements StackProps {

        String appName              =   null;
        private IRepository gitRepo =   null;     
        Environment env             =   null;
        Environment envTarget       =   null;
        Map<String,String> tags     =   null;
        Boolean terminationProtection   =   Boolean.FALSE;
        DeploymentConfig[] deploymentConfigs  =   null;      

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
            return "PipelineStack of the app "+getAppName();
        }

        public String getAppName(){
            return appName;
        }

        public Environment getEnv(){
            return env;
        }

        public IRepository getGitRepo() {
            return gitRepo;
        }

        public Environment getEnvTarget() {
            return envTarget;
        }

        public DeploymentConfig[] getDeploymentConfigs(){
            return deploymentConfigs;
        }

        public PipelineStackProps(String appName, IRepository gitRepo, Environment env, Environment envTarget, Map<String,String> tags, Boolean terminationProtection, DeploymentConfig[] deploymentConfigs){//} Role codeDeployRoleAlpha, IEcsDeploymentGroup dgAlpha, String deployConfigAlpha, Role codeDeployRoleBeta, IEcsDeploymentGroup dgBeta, String deployConfigBeta, DeploymentConfig[] deploymentConfigs){
            this.appName = appName;
            this.env = env;
            this.envTarget = envTarget;
            this.tags = tags;
            this.terminationProtection = terminationProtection;
            this.gitRepo = gitRepo;
            this.deploymentConfigs = deploymentConfigs;          
        }

        public static Builder builder(){
            return new Builder();
        }
        static class Builder{


            private String appName  =   null;
            private IRepository gitRepo =   null;
            private Environment env =   null;
            private Environment envTarget   =   null;
            private Map<String,String> tags =   null;
            private Boolean terminationProtection = Boolean.FALSE;
            private DeploymentConfig[] deploymentConfigs   =   null;

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

            public Builder gitRepo(IRepository gitRepo){
                this.gitRepo = gitRepo;
                return this;
            }

            public Builder envTarget(Environment envTarget){
                this.envTarget = envTarget;
                return this;
            }

            public Builder deploymentConfigs(DeploymentConfig[] deploymentConfigs){
                this.deploymentConfigs = deploymentConfigs;
                return this;
            }          

            public PipelineStackProps build(){
                return new PipelineStackProps(appName, gitRepo, env, envTarget, tags, terminationProtection, deploymentConfigs);// codeDeployRoleAlpha, dgAlpha, deployConfigAlpha, codeDeployRoleBeta, dgBeta, deployConfigBeta, deploymentConfigs);
            }
        }
    }
    
    public static class DeploymentConfig{

        static final String DEPLOY_LINEAR_10_PERCENT_EVERY_1_MINUTES = "CodeDeployDefault.ECSLinear10PercentEvery1Minutes";
        static final String DEPLOY_LINEAR_10_PERCENT_EVERY_3_MINUTES = "CodeDeployDefault.ECSLinear10PercentEvery3Minutes";
        static final String DEPLOY_CANARY_10_PERCENT_EVERY_5_MINUTES = "CodeDeployDefault.ECSCanary10percent5Minutes";
        static final String DEPLOY_CANARY_10_PERCENT_15_MINUTES = "CodeDeployDefault.ECSCanary10percent15Minutes";
        static final String DEPLOY_ALL_AT_ONCE = "CodeDeployDefault.ECSAllAtOnce";            

        private String deployConfig    =    null;
        private IEcsDeploymentGroup dg  =   null;
        private Role codeDeployRole =   null;
        private Environment env =   null;

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

        public DeploymentConfig(final String deployConfig, Environment env){
            this.deployConfig   =   deployConfig;
            this.env = env;
        }

        public DeploymentConfig(String deployConfig, Environment env, Role codeDeployRole, IEcsDeploymentGroup dg){
            this.codeDeployRole =   codeDeployRole;
            this.dg    =    dg;
            this.deployConfig   =   deployConfig;
            this.env    =   env;
        }
    }
}
