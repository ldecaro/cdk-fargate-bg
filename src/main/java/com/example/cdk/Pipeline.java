package com.example.cdk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.cdk.Infrastructure.ECSStackProps;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Stage;
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

public class Pipeline extends Stack {
    
    public Pipeline(Construct scope, String id, final PipelineStackProps props) throws Exception{

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

        software.amazon.awscdk.services.codepipeline.Pipeline codePipeline   =   software.amazon.awscdk.services.codepipeline.Pipeline.Builder.create(this, "-codepipeline")
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
                    "cdk synth -c appName=$APP_NAME -c alpha=$ALPHA -c beta=$BETA"))
                .build())
            .build();

        //processing list of deploymentConfigs, one per stage: alpha, beta, gamma
        processDeploymentConfig(props, pipelineRole);

        for(StageConfig deployConfig: props.getDeploymentConfigs()){
            
            final String envType =   deployConfig.getEnvType().toString().toLowerCase();
            ShellStep codeBuildPre = ShellStep.Builder.create("ConfigureBlueGreenDeploy")
                .input(pipeline.getCloudAssemblyFileSet())
                .additionalInputs(new HashMap<String,IFileSetProducer>(){{
                    put("../source", source);
                }})
                .primaryOutputDirectory("codedeploy")             
                .commands(configureCodeDeploy(appName, deployConfig ))
                .build();    

            Deploy deploy = new Deploy(this, 
                "Deploy-"+deployConfig.envType.getType(), 
                appName,
                deployConfig);

            StageDeployment stage = pipeline.addStage(deploy);
            stage.addPre(codeBuildPre);
            stage.addPost(new CodeDeployStep(
                "codeDeploy"+envType, 
                envType,
                codeBuildPre.getPrimaryOutput(), 
                deployConfig));
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
    protected class Deploy extends Stage {

        public Deploy(Construct scope, String id, String appName, StageConfig deploymentConfig) throws Exception { //String deployConfig, EnvType envType, StageProps props) throws  Exception{

            super(scope, id );

            new Infrastructure(this, 
                appName+"-infra-"+deploymentConfig.getEnvType().toString().toLowerCase(), 
                ECSStackProps.builder()
                    .appName(appName)
                    .deploymentConfig(deploymentConfig.getDeployConfig())
                    .stackName(appName+"-infra-"+deploymentConfig.getEnvType().toString().toLowerCase())
                    .env(deploymentConfig.getEnv())
                    .build());
        }
    }

    // CodeDeploy action executed after the ECSStack. It will control all deployments after ECSStack is created.
    private class CodeDeployStep extends Step implements ICodePipelineActionFactory{

        FileSet fileSet;
        Role codeDeployRole;
        IEcsDeploymentGroup dg;
        String envType;

        public CodeDeployStep(String id, String envType, FileSet fileSet, StageConfig deploymentConfig){//} Role codeDeployRole, IEcsDeploymentGroup dg){
            super(id);
            this.fileSet    =   fileSet;
            this.codeDeployRole =   deploymentConfig.getCodeDeployRole();
            this.dg    =   deploymentConfig.getEcsDeploymentGroup();
            this.envType = envType;
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
                .variablesNamespace("deployment-"+envType)
                .build());

            return CodePipelineActionFactoryResult.builder().runOrdersConsumed(1).build();
        }
    }

    private CodeBuildOptions getCodeBuildOptions(String appName, StageConfig[] deploymentConfigs ){

        List<PolicyStatement> policies = new ArrayList<>();
        policies.add(PolicyStatement.Builder.create()
            .effect(Effect.ALLOW)
            .actions(Arrays.asList("sts:AssumeRole")) 
            .resources(Arrays.asList("arn:aws:iam::*:role/cdk-*"))
            .build());

        HashMap<String,BuildEnvironmentVariable> envVars = new HashMap<>();
        envVars.put("APP_NANE", BuildEnvironmentVariable.builder()
            .type(BuildEnvironmentVariableType.PLAINTEXT)
            .value(appName == null ? "" : appName)
            .build());
        
        for(StageConfig deployConfig: deploymentConfigs){

            if( deployConfig.getCodeDeployRole()!=null){
                policies.add(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(Arrays.asList("sts:AssumeRole"))
                .resources(Arrays.asList( deployConfig.getCodeDeployRole().getRoleArn()) )
                .build());
            }
            //passing environment variables to codebuild so it respects the initial account setup
            envVars.put(deployConfig.getEnvType().getType().toUpperCase(), BuildEnvironmentVariable.builder()
                .type(BuildEnvironmentVariableType.PLAINTEXT)
                .value(deployConfig.getEnv().getAccount()+"/"+deployConfig.getEnv().getRegion())
                .build());
        }            

        return CodeBuildOptions.builder()
            .rolePolicy(policies)
            .buildEnvironment(BuildEnvironment.builder()
                .environmentVariables(envVars)
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
    private List<String> configureCodeDeploy(String appName, StageConfig deploymentConfig){

        final String strEnvType   =   deploymentConfig.getEnvType().toString().toLowerCase();
        final String account    =   deploymentConfig.getEnv().getAccount();
        final String region =   deploymentConfig.getEnv().getRegion();

        return Arrays.asList(
            "mkdir codedeploy",
            "ls -l",
            // "find . -type f -exec cat {} \\;",
            "cat *.assets.json",
            "export REPO_NAME=$(cat "+appName+"-svc-"+strEnvType+".assets.json | jq -r '.dockerImages[] | .destinations[] | .repositoryName')",
            "export TAG_NAME=$(cat "+appName+"-svc-"+strEnvType+".assets.json | jq -r '.dockerImages | keys[0]')",
            "echo $REPO_NAME",
            "echo $TAG_NAME",
            "printf '{\"ImageURI\":\"%s\"}' \""+account+".dkr.ecr."+region+".amazonaws.com/$REPO_NAME:$TAG_NAME\" > codedeploy/imageDetail.json",                    
            "sed 's#APPLICATION#"+appName+"#g' ../source/blue-green/template-appspec.yaml >> codedeploy/appspec.yaml",
            "sed 's#APPLICATION#"+appName+"#g' ../source/blue-green/template-taskdef.json | sed 's#TASK_EXEC_ROLE#"+"arn:aws:iam::"+account+":role/"+appName+"-"+strEnvType+"#g' | sed 's#fargate-task-definition#"+appName+"#g' >> codedeploy/taskdef.json",
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
        for(StageConfig deployConfig: props.getDeploymentConfigs()){

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
                            .deploymentGroupName( appName+"-"+deployConfig.getEnvType().toString().toLowerCase() )
                            .application(EcsApplication.fromEcsApplicationName(
                                this, 
                                appName+"-ecs-deploy-app", 
                                appName+"-"+deployConfig.getEnvType().toString().toLowerCase()))                                            
                            .build());                    
                }
                deployConfig.setCodeDeployRole(codeDeployRole);
                deployConfig.setDeploymentGroup(dg);
            }
        }
    }    

    protected static class PipelineStackProps implements StackProps {

        private String appName;
        private IRepository gitRepo;     
        private Environment env;
        private Environment envTarget;
        private Map<String,String> tags;
        private Boolean terminationProtection   =   Boolean.FALSE;
        private StageConfig[] deploymentConfigs;      

        @Override
        public @Nullable Map<String, String> getTags() {
            return tags;
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

        public StageConfig[] getDeploymentConfigs(){
            return deploymentConfigs;
        }

        public PipelineStackProps(String appName, IRepository gitRepo, Environment env, Environment envTarget, Map<String,String> tags, Boolean terminationProtection, StageConfig[] deploymentConfigs){
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


            private String appName;
            private IRepository gitRepo;
            private Environment env;
            private Environment envTarget;
            private Map<String,String> tags;
            private Boolean terminationProtection = Boolean.FALSE;
            private StageConfig[] deploymentConfigs;

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

            public Builder deploymentConfigs(StageConfig[] deploymentConfigs){

                // this.deploymentConfigs = deploymentConfigs;
                //order environments alphabetically alpha, beta, gamma
                this.deploymentConfigs = Arrays.stream(deploymentConfigs).sorted(Comparator.comparing(StageConfig::getEnvType)).toArray(StageConfig[]::new);
                for(StageConfig c: deploymentConfigs){
                    System.out.println(c);
                }

                return this;
            }          

            public PipelineStackProps build(){
                return new PipelineStackProps(appName, gitRepo, env, envTarget, tags, terminationProtection, deploymentConfigs);
            }
        }
    }
    
    public static class StageConfig{

        static final String DEPLOY_LINEAR_10_PERCENT_EVERY_1_MINUTES = "CodeDeployDefault.ECSLinear10PercentEvery1Minutes";
        static final String DEPLOY_LINEAR_10_PERCENT_EVERY_3_MINUTES = "CodeDeployDefault.ECSLinear10PercentEvery3Minutes";
        static final String DEPLOY_CANARY_10_PERCENT_EVERY_5_MINUTES = "CodeDeployDefault.ECSCanary10percent5Minutes";
        static final String DEPLOY_CANARY_10_PERCENT_15_MINUTES = "CodeDeployDefault.ECSCanary10percent15Minutes";
        static final String DEPLOY_ALL_AT_ONCE = "CodeDeployDefault.ECSAllAtOnce";            

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

        public StageConfig(final String deployConfig, Environment env, EnvType envType){
            this.deployConfig   =   deployConfig;
            this.env = env;
            this.envType    =   envType;
        }

        public EnvType getEnvType(){
            return this.envType;
        }

        public StageConfig(String deployConfig, Environment env, EnvType envType, Role codeDeployRole, IEcsDeploymentGroup dg){
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
}
