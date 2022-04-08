package com.example.cdk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.cdk.Infrastructure.ECSStackProps;
import com.example.cdk.application.CrossAccountService;
import com.example.cdk.application.CrossAccountService.CrossAccountApplicationProps;
import com.example.cdk.application.LocalService;
import com.example.cdk.application.Service;

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
import software.amazon.awscdk.services.iam.IRole;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Policy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.constructs.Construct;

public class Toolchain extends Stack {
    
    public Toolchain(Construct scope, String id, final PipelineStackProps props) throws Exception{

        super(scope, id, props);

        String appName  =   props.getAppName();
        IRepository gitRepo =   props.getGitRepo();  

        Environment envAlpha = Util.makeEnv((String)scope.getNode().tryGetContext("alpha"));
        Environment envBeta = Util.makeEnv((String)scope.getNode().tryGetContext("beta"));

        Service alpha = createService(scope,
            StageConfig.EnvType.ALPHA, 
            envAlpha,   
            StageConfig.DEPLOY_ALL_AT_ONCE, 
            props);

        Service beta = createService(scope,
            StageConfig.EnvType.BETA, 
            envBeta, 
            StageConfig.DEPLOY_LINEAR_10_PERCENT_EVERY_1_MINUTES, 
            props); 

        CodePipelineSource  source  =   CodePipelineSource.codeCommit(
            gitRepo,
            "main",
            CodeCommitSourceOptions
                .builder()
                .trigger(CodeCommitTrigger.POLL)
                .build());           

        //we need to create a reference pipeline to add the role otherwise it throws Error: Pipeline not created yet
        IRole pipelineRole   =   createPipelineRole(appName);  

        software.amazon.awscdk.services.codepipeline.Pipeline codePipeline   =   software.amazon.awscdk.services.codepipeline.Pipeline.Builder.create(this, "-codepipeline")
            .role(pipelineRole)
            .crossAccountKeys(Boolean.TRUE)
            .restartExecutionOnUpdate(Boolean.TRUE)
            .build();

        CodePipeline pipeline   =   CodePipeline.Builder.create(this, appName+"-pipeline")
            .codePipeline(codePipeline)
            .selfMutation(Boolean.TRUE)
            .publishAssetsInParallel(Boolean.FALSE)
            .dockerEnabledForSelfMutation(Boolean.TRUE)
            .synthCodeBuildDefaults(getCodeBuildOptions(appName, new StageConfig[]{alpha.getDeploymentConfig(), beta.getDeploymentConfig()}))
            .selfMutationCodeBuildDefaults(getCodeBuildOptions(appName, new StageConfig[]{alpha.getDeploymentConfig(), beta.getDeploymentConfig()}))
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

        configureStage(alpha, pipeline, pipelineRole, source, props);
        configureStage(beta, pipeline, pipelineRole, source, props);

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

    private Service createService(Construct scope, StageConfig.EnvType envType, Environment env, String deploymentConfig, final PipelineStackProps props){

        Service application;
        if( props.getEnv().equals(env) ){
            System.out.println("Env "+envType+" is in the same account");
            application = new LocalService(
                scope, 
                props.getAppName(),
                deploymentConfig,
                envType,
                StackProps.builder()
                    .env(env)
                    .stackName(props.getAppName()+"-svc-"+envType.toString().toLowerCase())
                    .build());       
        }else{
            System.out.println("Env "+envType+" is in a cross account");
            application = new CrossAccountService(
                scope,
                props.getAppName(),
                deploymentConfig,
                envType,
                CrossAccountApplicationProps.builder()
                    .env(env)
                    .envPipeline(props.getEnv())
                    .stackName(props.getAppName()+"-svc-"+envType.toString().toLowerCase())
                    .build()
                );
        }    
        this.addDependency((Stack)application);   
        return application;
    }

    public void configureStage(Service stageConfig, CodePipeline pipeline, IRole role, CodePipelineSource source, PipelineStackProps props){

        StageConfig deployConfig =   stageConfig.getDeploymentConfig();
        processDeploymentConfig(props.getAppName(), deployConfig, role);
            
        final String strEnvType =   deployConfig.getEnvType().toString().toLowerCase();
        ShellStep codeBuildPre = ShellStep.Builder.create("ConfigureBlueGreenDeploy")
            .input(pipeline.getCloudAssemblyFileSet())
            .additionalInputs(new HashMap<String,IFileSetProducer>(){{
                put("../source", source);
            }})
            .primaryOutputDirectory("codedeploy")             
            .commands(configureCodeDeploy(props.getAppName(), deployConfig ))
            .build();    

        Deploy deploy = new Deploy(this, 
            "Deploy-"+deployConfig.getEnvType().getType(), 
            props.getAppName(),
            deployConfig);

        StageDeployment stageDeployment = pipeline.addStage(deploy);
        
        stageDeployment.addPre(codeBuildPre);
        stageDeployment.addPost(new CodeDeployStep(
            "codeDeploy"+deployConfig.getEnvType().toString(), 
            strEnvType,
            codeBuildPre.getPrimaryOutput(), 
            deployConfig));
    }

    protected class Deploy extends Stage {

        public Deploy(Construct scope, String id, String appName, StageConfig deploymentConfig) { //String deployConfig, EnvType envType, StageProps props) throws  Exception{

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

    private class CodeDeployStep extends Step implements ICodePipelineActionFactory{

        FileSet fileSet;
        Role codeDeployRole;
        IEcsDeploymentGroup dg;
        String envType;

        public CodeDeployStep(String id, String envType, FileSet fileSet, StageConfig deploymentConfig){
            super(id);
            this.fileSet    =   fileSet;
            this.codeDeployRole =   deploymentConfig.getCodeDeployRole();
            if(deploymentConfig.getEcsDeploymentGroup() == null ){
                throw new IllegalArgumentException("EcsDeploymentGroup cannot be null");
            }
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
        for(StageConfig config: deploymentConfigs){
            // 12346787901/us-east-1
            envVars.put(config.getEnvType().toString().toUpperCase(), BuildEnvironmentVariable.builder()
            .type(BuildEnvironmentVariableType.PLAINTEXT)
            .value(config.getEnv().getAccount()+"/"+config.getEnv().getRegion())
            .build());
        }
        
        for(StageConfig deployConfig: deploymentConfigs){

            if( deployConfig.getCodeDeployRole()!=null){
                policies.add(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(Arrays.asList("sts:AssumeRole"))
                .resources(Arrays.asList( deployConfig.getCodeDeployRole().getRoleArn()) )
                .build());
            }
            //passing environment variables to codebuild so it respects the initial account configuration
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
                .privileged(Boolean.FALSE)
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

    private void processDeploymentConfig(final String appName, final StageConfig deployConfig, final IRole codePipelineRole){
        
        IEcsDeploymentGroup dg    = null;
        Role codeDeployRole =   null;

        if( deployConfig.getCodeDeployRole()!=null ){

            Policy policy = Policy.Builder.create(this, "assumeRolePolicy").build();
            policy.addStatements(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(Arrays.asList("sts:AssumeRole"))
                .resources(Arrays.asList( deployConfig.getCodeDeployRole().getRoleArn()))
                .build());
            codePipelineRole.attachInlinePolicy( policy );
            
        }else{

            if(codeDeployRole == null){

                codeDeployRole   =  Role.Builder.create(this, appName+"-codedeploy-role-"+deployConfig.getEnvType().toString())
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
                    appName+"-ecsdeploymentgroup-"+deployConfig.getEnvType().toString(), 
                    EcsDeploymentGroupAttributes.builder()
                        .deploymentGroupName( appName+"-"+deployConfig.getEnvType().toString().toLowerCase() )
                        .application(EcsApplication.fromEcsApplicationName(
                            this, 
                            appName+"-ecs-deploy-app-"+deployConfig.getEnvType().toString(), 
                            appName+"-"+deployConfig.getEnvType().toString().toLowerCase()))                                            
                        .build());                    
            }
            deployConfig.setCodeDeployRole(codeDeployRole);
            deployConfig.setDeploymentGroup(dg);
        }
    }    

    protected static class PipelineStackProps implements StackProps {

        private String appName;
        private IRepository gitRepo;     
        private Environment env;
        private Map<String,String> tags;
        private Boolean terminationProtection   =   Boolean.FALSE;   

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


        public PipelineStackProps(String appName, IRepository gitRepo, Environment env,Map<String,String> tags, Boolean terminationProtection){
            this.appName = appName;
            this.env = env;
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
            private Environment env;
            private Map<String,String> tags;
            private Boolean terminationProtection = Boolean.FALSE;

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

            public PipelineStackProps build(){
                return new PipelineStackProps(appName, gitRepo, env, tags, terminationProtection);
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
