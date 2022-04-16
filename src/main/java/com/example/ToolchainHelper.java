package com.example;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import com.example.api.infrastructure.CrossAccountService;
import com.example.api.infrastructure.CrossAccountServiceProps;
import com.example.api.infrastructure.LocalService;
import com.example.api.infrastructure.Service;

import org.jetbrains.annotations.NotNull;

import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
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
import software.amazon.awscdk.services.iam.IRole;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Policy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.constructs.Construct;

public class ToolchainHelper {

    private Construct scope =   null;

    CodePipelineSource source   =   null;

    public ToolchainHelper(Construct scope){
        this.scope  =   scope;
    }
    
    IRole createPipelineRole(final String appName){

        return Role.Builder.create(scope, appName+"-pipelineRole")
            .assumedBy(ServicePrincipal.Builder.create("codepipeline.amazonaws.com").build())
            .roleName(appName+"-pipelineRole")
            .build();
    }

    Pipeline createTemplatePipeline(IRole pipelineRole){

        return Pipeline.Builder.create(scope, "-codepipeline")
        .role(pipelineRole)
        .crossAccountKeys(Boolean.TRUE)
        .restartExecutionOnUpdate(Boolean.TRUE)
        .build();        
    }    

    CodePipeline createPipeline(final String appName, IRepository repo, Pipeline templatePipeline, DeploymentConfig[] deploymentConfig ){

        CodePipelineSource  source  =   CodePipelineSource.codeCommit(
            repo,
            "main",
            CodeCommitSourceOptions
                .builder()
                .trigger(CodeCommitTrigger.POLL)
                .build());   

        this.source = source;
        
        return CodePipeline.Builder.create(scope, appName+"-pipeline")
        .codePipeline(templatePipeline)
        .selfMutation(Boolean.TRUE)
        .publishAssetsInParallel(Boolean.FALSE)
        .dockerEnabledForSelfMutation(Boolean.TRUE)
        .synthCodeBuildDefaults(getCodeBuildOptions(appName, deploymentConfig))
        .selfMutationCodeBuildDefaults(getCodeBuildOptions(appName, deploymentConfig))
        .synth(ShellStep.Builder.create(appName+"-synth")
            .input(source)
            .installCommands(Arrays.asList(
                "npm install -g aws-cdk"
                ))
            .commands(Arrays.asList(
                "mkdir cdk.out",
                "mvn -B clean package",
                "cd target && ls -d  */ | xargs rm -rf && ls -lah && cd ..", //clean up target folder
                "cdk synth -c appName=$APP_NAME -c alpha=$ALPHA -c beta=$BETA -c gamma=$GAMMA"))
            .build())
        .build(); 
    }

    void processDeploymentConfig(final String appName, final DeploymentConfig deployConfig, final IRole codePipelineRole){
        
        IEcsDeploymentGroup dg    = null;
        Role codeDeployRole =   null;
        //if this is a cross-account scenario we assumeRole from remote account.
        if( deployConfig.getCodeDeployRole()!=null ){

            Policy policy = Policy.Builder.create(scope, "assumeRolePolicy").build();
            policy.addStatements(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(Arrays.asList("sts:AssumeRole"))
                .resources(Arrays.asList( deployConfig.getCodeDeployRole().getRoleArn()))
                .build());
            codePipelineRole.attachInlinePolicy( policy );
            
        }else{
        //if this is a single account scenario we create codeDeployRole specific to the environmentType
            if(codeDeployRole == null){

                codeDeployRole   =  Role.Builder.create(scope, appName+"-codedeploy-role-"+deployConfig.getEnvType().toString())
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
                    scope, 
                    appName+"-ecsdeploymentgroup-"+deployConfig.getEnvType().toString(), 
                    EcsDeploymentGroupAttributes.builder()
                        .deploymentGroupName( appName+"-"+deployConfig.getEnvType().toString().toLowerCase() )
                        .application(EcsApplication.fromEcsApplicationName(
                            scope, 
                            appName+"-ecs-deploy-app-"+deployConfig.getEnvType().toString(), 
                            appName+"-"+deployConfig.getEnvType().toString().toLowerCase()))                                            
                        .build());                    
            }
            deployConfig.setCodeDeployRole(codeDeployRole);
            deployConfig.setDeploymentGroup(dg);
        }
    }        

    /**
     * Configures appspec.yaml, taskdef.json and imageDetails.json using information coming from the ApplicationStack/DockerImageAsset (.assets)
     * @param appName
     * @param targetEnv
     * @param stageNumber
     * @return
     */
    List<String> configureCodeDeploy(String appName, DeploymentConfig deploymentConfig){

        final String strEnvType   =   deploymentConfig.getEnvType().toString().toLowerCase();
        final String account    =   deploymentConfig.getEnv().getAccount();
        final String region =   deploymentConfig.getEnv().getRegion();

        return Arrays.asList(
            "mkdir codedeploy",
            "ls -l",
            "cat *.assets.json",
            "export REPO_NAME=$(cat *"+strEnvType+"*.assets.json | jq -r '.dockerImages[] | .destinations[] | .repositoryName')",
            "export TAG_NAME=$(cat *"+strEnvType+"*.assets.json | jq -r '.dockerImages | keys[0]')",
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

    Service createService(DeploymentConfig.EnvType envType, Environment env, String deploymentConfig, final ToolchainStackProps props){

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
                CrossAccountServiceProps.builder()
                    .env(env)
                    .envPipeline(props.getEnv())
                    .stackName(props.getAppName()+"-svc-"+envType.toString().toLowerCase())
                    .build()
                );
        }    
        ((Stack)scope).addDependency((Stack)application);   
        return application;
    }    

    CodeBuildOptions getCodeBuildOptions(String appName, DeploymentConfig[] deploymentConfigs ){

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
        for(DeploymentConfig config: deploymentConfigs){
            // 12346787901/us-east-1
            envVars.put(config.getEnvType().toString().toUpperCase(), BuildEnvironmentVariable.builder()
            .type(BuildEnvironmentVariableType.PLAINTEXT)
            .value(config.getEnv().getAccount()+"/"+config.getEnv().getRegion())
            .build());
        }
        
        for(DeploymentConfig deployConfig: deploymentConfigs){

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
    
    public CodePipelineSource getCodePipelineSource(){
        return this.source;
    }

    static class CodeDeployStep extends Step implements ICodePipelineActionFactory{

        FileSet fileSet;
        Role codeDeployRole;
        IEcsDeploymentGroup dg;
        String envType;

        public CodeDeployStep(String id, String envType, FileSet fileSet, DeploymentConfig deploymentConfig){
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

    void configureDeployStage(Service service, CodePipeline pipeline, IRole role, CodePipelineSource source, ToolchainStackProps props){

        DeploymentConfig deployConfig =   service.getDeploymentConfig();
        this.processDeploymentConfig(props.getAppName(), deployConfig, role);
            
        final String strEnvType =   deployConfig.getEnvType().toString().toLowerCase();
        ShellStep codeBuildPre = ShellStep.Builder.create("ConfigureBlueGreenDeploy")
            .input(pipeline.getCloudAssemblyFileSet())
            .additionalInputs(new HashMap<String,IFileSetProducer>(){{
                put("../source", source);
            }})
            .primaryOutputDirectory("codedeploy")             
            .commands(configureCodeDeploy(props.getAppName(), deployConfig ))
            .build();    

        Deployment deploy = new Deployment(scope, 
            "Deploy-"+deployConfig.getEnvType().getType(), 
            props.getAppName(),
            deployConfig);

        StageDeployment stageDeployment = pipeline.addStage(deploy);
        
        stageDeployment.addPre(codeBuildPre);
        stageDeployment.addPost(new ToolchainHelper.CodeDeployStep(
            "codeDeploy"+deployConfig.getEnvType().toString(), 
            strEnvType,
            codeBuildPre.getPrimaryOutput(), 
            deployConfig));
    }

}
