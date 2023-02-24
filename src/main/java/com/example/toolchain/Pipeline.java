package com.example.toolchain;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.example.Constants;
import com.example.webapp.WebApp;

import software.amazon.awscdk.Arn;
import software.amazon.awscdk.ArnComponents;
import software.amazon.awscdk.ArnFormat;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Stage;
import software.amazon.awscdk.pipelines.CodeCommitSourceOptions;
import software.amazon.awscdk.pipelines.CodePipeline;
import software.amazon.awscdk.pipelines.CodePipelineSource;
import software.amazon.awscdk.pipelines.ManualApprovalStep;
import software.amazon.awscdk.pipelines.ShellStep;
import software.amazon.awscdk.pipelines.StageDeployment;
import software.amazon.awscdk.pipelines.Step;
import software.amazon.awscdk.services.codecommit.Repository;
import software.amazon.awscdk.services.codedeploy.EcsApplication;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentGroup;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentGroupAttributes;
import software.amazon.awscdk.services.codedeploy.IEcsApplication;
import software.amazon.awscdk.services.codedeploy.IEcsDeploymentConfig;
import software.amazon.awscdk.services.codedeploy.IEcsDeploymentGroup;
import software.amazon.awscdk.services.codepipeline.actions.CodeCommitTrigger;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.constructs.Construct;

public class Pipeline extends Construct {

    public static final Boolean CONTINUOUS_DELIVERY       = Boolean.TRUE;
    public static final Boolean CONTINUOUS_DEPLOYMENT       = Boolean.FALSE;

    private String pipelineAccount  =   null;
    private CodePipeline pipeline   =   null;

    private Map<String, Environment> stageEnvironment  =   new HashMap<>();

    private Pipeline(Construct scope, final String id, final String gitRepoURL, final String gitBranch){

        super(scope,id);

        pipelineAccount = Stack.of(scope).getAccount();

        pipeline   =   createPipeline(
            gitRepoURL,
            gitBranch);
    }

    private Pipeline addStage(final String stageName, final IEcsDeploymentConfig deployConfig, final Environment env, final Boolean ADD_APPROVAL ) {


        //The stage
        Stage deployStage = Stage.Builder.create(pipeline, stageName).env(env).build();


        // My stack
        new WebApp(
            deployStage, 
            "Component"+stageName,
            deployConfig,
            StackProps.builder()
                .stackName(Constants.APP_NAME+stageName)
                .description(Constants.APP_NAME+"-"+stageName)
                .build());                 

        //Configure AWS CodeDeploy
        Step configCodeDeployStep = ShellStep.Builder.create("ConfigureBlueGreenDeploy")
            .input(pipeline.getCloudAssemblyFileSet())
            .primaryOutputDirectory("codedeploy")    
            .commands(configureCodeDeploy(stageName, env.getAccount(), env.getRegion()))
            .build(); 
 
        StageDeployment stageDeployment = pipeline.addStage(deployStage);

        if(ADD_APPROVAL){
            stageDeployment.addPre(ManualApprovalStep.Builder.create("Approve "+stageName).build(), configCodeDeployStep);
        }else{
            stageDeployment.addPre(configCodeDeployStep);
        }               

        //Deploy using AWS CodeDeploy
        stageDeployment.addPost(
            new CodeDeployStep(            
            "codeDeploypreprod", 
            configCodeDeployStep.getPrimaryOutput(),
            importCodeDeployDeploymentGroup(env, stageName, deployConfig),
            stageName)
        );

        if( pipeline.getSelfMutationEnabled() && pipelineAccount != env.getAccount() ){
            stageEnvironment.put(stageName, env);
        }
        
        return this;
    }

    /**
     * Self-mutating pipelines create a stage named UpdatePipeline. 
     * AWS CodeDeploy uses configuration files to work properly and, depending on the use case (cross-account), 
     * this files might need to be transferred to the target account. In order to transfer files, the CDK uses
     * an account support stack with the prefix cross-account-support*. That stack needs permission to publish
     * CodeDeploy artifacts in the target account and those permissions need to be associated with the role the
     * UpdatePipeline project uses.
     * 
     * In detail, the information about stacks that depend on the current stack is only available at the time the app
     * finishes synthesizing, and by that point, we have already locked-in the permissions, because they are part
     * of the step.
     * 
     * So, in order to overcome this limitation, we are changing the role UpdatePipeline uses, allowing the 
     * cross-account-support* stack to do file-publishing to the target account.
     * 
     * (https://github.com/aws/aws-cdk/pull/24073)
     * 
     */
    private void buildPipeline(){

        if(!this.stageEnvironment.isEmpty()){

            this.pipeline.buildPipeline();
            for(String stage: stageEnvironment.keySet()){
                 
                HashMap<String,String[]> condition	=	new HashMap<>();
                condition.put("iam:ResourceTag/aws-cdk:bootstrap-role", new String[]{"image-publishing", "file-publishing", "deploy"});            
                pipeline.getSelfMutationProject()
                    .getRole().addToPrincipalPolicy(PolicyStatement.Builder.create()
                        .actions(Arrays.asList("sts:AssumeRole"))
                        .effect(Effect.ALLOW)                        
                        .resources(Arrays.asList("arn:*:iam::"+stageEnvironment.get(stage).getAccount()+":role/*"))
                        .conditions(new HashMap<String,Object> () {{put("ForAnyValue:StringEquals", condition);}})
                        .build()                    
                    );              
            }
        }
    }

    private IEcsDeploymentGroup importCodeDeployDeploymentGroup(final Environment env, final String stageName, final IEcsDeploymentConfig deployConfig){

        IEcsApplication codeDeployApp = EcsApplication.fromEcsApplicationArn(
            this, 
            Constants.APP_NAME+"-ecs-deploy-app", 
            Arn.format(ArnComponents.builder()
                .arnFormat(ArnFormat.COLON_RESOURCE_NAME)
                .partition("aws")
                .region(env.getRegion()) // IAM is global in each partition
                .service("codedeploy")
                .account(env.getAccount())
                .resource("application")
                .resourceName(Constants.APP_NAME+"-"+stageName)
                .build())
            );

        IEcsDeploymentGroup deploymentGroup = EcsDeploymentGroup.fromEcsDeploymentGroupAttributes(
            this, 
            Constants.APP_NAME+"-DeploymentGroup",
            EcsDeploymentGroupAttributes.builder()
                .deploymentGroupName(Constants.APP_NAME+"-"+stageName)
                .application(codeDeployApp)
                .deploymentConfig(deployConfig)
                .build()
            );  

        return deploymentGroup;
    }

    /**
     * Configures the appspec.yaml and taskdef.json replacing tokens with environment data.
     * @param stageName
     * @param account
     * @param region
     * @return
     */
    private List<String> configureCodeDeploy(final String stageName, String account, String region ){

        final String pipelineId    =   ((Construct)pipeline).getNode().getId();

        try {

            String path = "target/classes/"+this.getClass().getName().substring(0, this.getClass().getName().lastIndexOf(".")).replace(".", "/")+"/";
            File f = new File(path+"codedeploy_configuration.txt");
            System.out.println(f.getCanonicalPath());

            List<String> commands = Files.lines(Paths.get(path+"codedeploy_configuration.txt"))                
                .map(line->line.replace("{Account}", account))
                .map(line->line.replace("{Region}", region))
                .map(line->line.replace("{AppName}", Constants.APP_NAME))
                .map(line->line.replace("{StageName}", stageName))
                .map(line->line.replace("{PipelineId}", pipelineId))
            .collect(Collectors.toList());

            System.out.println(commands);

            return commands;

        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("Could not configure CodeDeploy. Could not load file codedeploy_configuration.txt");
        }
  
    }     

    private CodePipeline createPipeline(String repoURL, String branch){

        CodePipelineSource  source  =   CodePipelineSource.codeCommit(
            Repository.fromRepositoryName(this, "code-repository", repoURL ),
            branch,
            CodeCommitSourceOptions
                .builder()
                .trigger(CodeCommitTrigger.POLL)
                .build());   
        
        return CodePipeline.Builder.create(this, "Pipeline-"+Constants.APP_NAME)
            .publishAssetsInParallel(Boolean.FALSE)
            .dockerEnabledForSelfMutation(Boolean.TRUE)
            .crossAccountKeys(Boolean.TRUE)
            .synth(ShellStep.Builder.create(Constants.APP_NAME+"-synth")
                .input(source)
                .installCommands(Arrays.asList(
                    "npm install"))
                .commands(Arrays.asList(
                    "mvn -B clean package",
                    "npx cdk synth"))
                .build())
            .build();
    }  

    public static final class Builder implements software.amazon.jsii.Builder<Pipeline>{

        private Construct scope;
        private String id;
        private String gitRepoURL;
        private String gitBranch; 
        private List<DeployConfig> stages = new ArrayList<>();

        private Builder(final Construct scope, final String id){
            this.scope = scope;
            this.id = id;
        }

        public static Builder create(Construct scope, final String id){
            return new Builder(scope, id);
        }

        public Builder setGitRepo(String gitRepoURL){
            this.gitRepoURL = gitRepoURL;
            return this;
        }

        public Builder setGitBranch(String gitBranch){
            this.gitBranch = gitBranch;
            return this;
        }

        public Builder addStage(String name, IEcsDeploymentConfig deployConfig, Environment env){
            this.stages.add(new DeployConfig(name, deployConfig, env));
            return this;
        }

        public Builder addStageWithApproval(String name, IEcsDeploymentConfig deployConfig, Environment env){
            this.stages.add(new DeployConfig(name, deployConfig, env, Boolean.TRUE));
            return this;
        }        

        public Pipeline build(){
            Pipeline p = new Pipeline(this.scope, this.id, this.gitRepoURL, this.gitBranch );
            for(DeployConfig d: stages){
                p.addStage(d.getName(), d.getDeployConfig(), d.getEnv(), d.getApproval());
            }
            p.buildPipeline();
            return p;
        }

        private static final class DeployConfig{

            String name;
            IEcsDeploymentConfig deployConfig;
            Environment env;
            Boolean approval = Boolean.FALSE;
            
            private DeployConfig(String name, IEcsDeploymentConfig deployConfig, Environment env){
                this.name = name;
                this.deployConfig = deployConfig;
                this.env = env;
            }

            private DeployConfig(String name, IEcsDeploymentConfig deployConfig, Environment env, final Boolean IS_APPROVAL){
                this.name = name;
                this.deployConfig = deployConfig;
                this.env = env;
                this.approval = IS_APPROVAL;
            }

            public String getName(){
                return name;
            }

            public IEcsDeploymentConfig getDeployConfig(){
                return deployConfig;
            }

            public Environment getEnv(){
                return env;
            }

            public Boolean getApproval(){
                return approval;
            }
        }
    }

}