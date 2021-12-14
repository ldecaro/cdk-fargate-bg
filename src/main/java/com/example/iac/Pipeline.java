package com.example.iac;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipOutputStream;

import software.amazon.awscdk.CustomResource;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.customresources.Provider;
import software.amazon.awscdk.services.codebuild.BuildEnvironment;
import software.amazon.awscdk.services.codebuild.ComputeType;
import software.amazon.awscdk.services.codebuild.IProject;
import software.amazon.awscdk.services.codebuild.LinuxBuildImage;
import software.amazon.awscdk.services.codebuild.PipelineProject;
import software.amazon.awscdk.services.codecommit.CfnRepository;
import software.amazon.awscdk.services.codecommit.CfnRepository.CodeProperty;
import software.amazon.awscdk.services.codecommit.CfnRepository.S3Property;
import software.amazon.awscdk.services.codecommit.IRepository;
import software.amazon.awscdk.services.codecommit.Repository;
import software.amazon.awscdk.services.codepipeline.Artifact;
import software.amazon.awscdk.services.codepipeline.IStage;
import software.amazon.awscdk.services.codepipeline.StageOptions;
import software.amazon.awscdk.services.codepipeline.actions.CodeBuildAction;
import software.amazon.awscdk.services.codepipeline.actions.CodeBuildActionType;
import software.amazon.awscdk.services.codepipeline.actions.CodeCommitSourceAction;
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
import software.amazon.awscdk.services.s3.deployment.BucketDeployment;
import software.amazon.awscdk.services.s3.deployment.ISource;
import software.amazon.awscdk.services.s3.deployment.Source;
import software.constructs.Construct;

public class Pipeline extends Stack {

    private software.amazon.awscdk.services.codepipeline.Pipeline pipeline;
    private Artifact build;
    private Artifact source;
    private Role deployRole;
    
    public Pipeline(Construct scope, String id, final String appName, final  ECSPlane ecs, final StackProps props) throws Exception{

        super(scope, id, props);
        //creating the configuration files: appspec.yaml, buildspec.yml and taskdef.json

        final String listenerBlueArn = ecs.getListenerBlueArn();
        final String listenerGreenArn   =   ecs.getListenerGreenArn();
        final String tgBlueName =   ecs.getTgBlueName();
        final String tgGreenName=   ecs.getTgGreenName();
        final String ecsTaskExecutionRole =   ecs.getEcsTaskExecutionRoleName();

        updateConfigurationFiles(appName, props.getEnv().getAccount(), props.getEnv().getRegion(), ecsTaskExecutionRole);

        //pack
        Pipeline.createSrcZip(appName);

        //creating the ECR Repository
        software.amazon.awscdk.services.ecr.Repository repo =   software.amazon.awscdk.services.ecr.Repository.Builder.create(this, appName+"-ecr").repositoryName(appName+"").build();
        repo.applyRemovalPolicy(RemovalPolicy.DESTROY);

        //code commit
		//s3 bucket to deploy the CodePipeline Repository
		Bucket gitSeed = Bucket.Builder.create(this, "GitSeedBucket")
										.bucketName(appName+"-src-"+Util.randomString(4))
										.encryption(BucketEncryption.S3_MANAGED)
                                        .removalPolicy(RemovalPolicy.DESTROY)
										.build();
								
		BucketDeployment s3Deployment = BucketDeployment.Builder.create(this, "GitSeedBucketDeployment")
								.sources(Arrays.asList(new ISource[]{ Source.asset("./dist") }))
								.destinationBucket(gitSeed)
                                .retainOnDelete(Boolean.FALSE)
								.build();	
								
		//create CodeCommit repository using the S3 bucket containing the src project
		String gitRepoName = appName;//+"-repo-"+Util.randomString(4);
		IRepository gitRepo = Repository.Builder.create(this, "CodeCommitRepository")
							.description("This is a repository for project "+appName)
							.repositoryName(gitRepoName)
							.build();

		//makig sure we guarantee the correct order of events
		gitRepo.getNode().addDependency(s3Deployment);
							
		((CfnRepository)gitRepo
							.getNode()
							.getDefaultChild())
							.setCode(CodeProperty
										.builder()
										.s3(S3Property
											.builder()
											.bucket(gitSeed.getBucketName())
											.key(appName+"-src.zip")
											.build())
										.build());

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

        gitRepo.grantPull( pipelineRole );
        // gitRepo.grantRead( pipelineRole );
        IStage source = pipe.addStage(StageOptions.builder().stageName("Source").build());
            
        //src
        Artifact sourceArtifact = new Artifact("SourceArtifact");
        Artifact buildArtifact  =   new Artifact("BuildArtifact");

        source.addAction(CodeCommitSourceAction.Builder.create()
                        .actionName("Source")
                        .branch("main")
                        .output(sourceArtifact)
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
        // https://bezdelev.com/hacking/aws-cli-inside-lambda-layer-aws-s3-sync/   
        
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

        // testar e criar a custom resource que execute a chamada da lambda...passar no environmnet 3 parametros pequenos que eu quero usar.
        // Ver a funcao do dynamodb em realtime-marketdate e analisar como que eu chamo a lambda

        // //vou criar o bluegreen deployment group using cfn L1 e depois vou importar direto, porém eu acho que o CFN não tem o Blue/Green
        // CfnApplication deployApp = CfnApplication.Builder.create(this, appName+"deploy-app").applicationName(appName).computePlatform("ECS").build();
        // CfnDeploymentGroup deployDg =   CfnDeploymentGroup.Builder.create(this, appName+"-deploy-dg")
        //                                                             .deploymentGroupName( appName+"-deploy-dg")
        //                                                             .deploymentConfigName("CodeDeployDefault.ECSLinear10PercentEvery1Minutes")
        //                                                             .applicationName(deployApp.getApplicationName())
        //                                                             .serviceRoleArn(deployRole.getRoleArn())
        //                                                             .deploymentStyle(DeploymentStyleProperty.builder().deploymentOption("WITH_TRAFFIC_CONTROL").deploymentType("BLUE_GREEN").build())
        //                                                             .blueGreenDeploymentConfiguration( BlueGreenDeploymentConfigurationProperty.builder()
        //                                                                                                 .terminateBlueInstancesOnDeploymentSuccess(BlueInstanceTerminationOptionProperty.builder().action("TERMINATE").terminationWaitTimeInMinutes(60).build())
        //                                                                                                 .deploymentReadyOption(DeploymentReadyOptionProperty.builder().actionOnTimeout("CONTINUE_DEPLOYMENT").waitTimeInMinutes(0).build())
        //                                                                                                 .build() )
        //                                                             .loadBalancerInfo(LoadBalancerInfoProperty.builder().targetGroupInfoList(Arrays.asList(TargetGroupInfoProperty.builder().name(appName+"-Blue").build(), TargetGroupInfoProperty.builder().name(appName+"-Green").build())).build())
        //                                                             .ecsServices(Arrays.asList(ECSServiceProperty.builder().clusterName(appName).serviceName(appName).build()))
        //                                                             .build();
        
        // IStage deploy   =   pipe.addStage(StageOptions.builder().stageName("Deploy").build() );
        // deploy.addAction(  CodeDeployEcsDeployAction.Builder.create()
        //                 .actionName("Deploy")
        //                 .role(deployRole)
        //                 .appSpecTemplateInput(buildArtifact)
        //                 .taskDefinitionTemplateInput(buildArtifact)
        //                 .containerImageInputs(Arrays.asList(CodeDeployEcsContainerImageInput.builder()
        //                                         .input(buildArtifact)
        //                                         // the properties below are optional
        //                                         .taskDefinitionPlaceholder("IMAGE1_NAME")
        //                                         .build()))
        //                 .deploymentGroup(	EcsDeploymentGroup.fromEcsDeploymentGroupAttributes(this, appName+"-ecsdeploymentgroup", EcsDeploymentGroupAttributes.builder()
        //                                     .deploymentGroupName( deployDg.getDeploymentGroupName() )
        //                                     .application(EcsApplication.fromEcsApplicationName(this, appName+"-ecs-deploy-app", deployApp.getApplicationName()))
        //                                     // pode ser aqui o meu problema depois por não ter associado o deployment config name
        //                                     .build()))
        //                 .variablesNamespace("deployment")
        //                 .build()
        // );

    }

    // private IProject createDeployProject(String appName, Role deployRole){
    //     return null;
    // }

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
                            // .inlinePolicies(new HashMap<String, PolicyDocument>(){
                            //     private static final long serialVersionUID = 6728018370248392226L;
                            //     {
                            //         put(appName+"AssumeRolePolicy", 		
                            //                 PolicyDocument.Builder.create().statements(				
                            //                 Arrays.asList(				
                            //                         PolicyStatement.Builder.create()
                            //                             .actions(Arrays.asList("sts:AssumeRole"))
                            //                             .effect(Effect.ALLOW)
                            //                             .sid("CodeDeployBlueGreenAssumeRole")
                            //                             .resources(Arrays.asList("arn:aws:iam::"+props.getEnv().getAccount()+":role/"+deployRole.getRoleName()))
                            //                             .build())).build());
                            //     }
                            // }
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

    private void updateConfigurationFiles(String appName, String account, String region, String ecsTaskExecutionRole){

        Util util   =   new Util();
        //buildspec.yml
        util.updateFile("buildspec-template.yml", "buildspec.yml", new HashMap<String,String>(){{
            put("ACCT_NUMBER", account);
            put("APPLICATION", appName);
            put("REGION", region);
        }});
        //appspec.yml
        util.updateFile("appspec-template.yaml", "appspec.yaml", "APPLICATION", appName);
        //taskdef
        util.updateFile("taskdef-template.json", "taskdef.json", new HashMap<String,String>(){{
            put("APPLICATION", appName);
            put("TASK_EXEC_ROLE", "arn:aws:iam::"+account+":role/"+ecsTaskExecutionRole);
        }});        
             
    }

    private static void createSrcZip(final String appName) throws Exception {

        //zip do proprio projeto dentro do diretorio raiz
        // System.out.println("Zipping project inside directory "+(System.getProperty("user.dir")+"/dist"));

        File outputFile = new File("dist");
        if(outputFile.exists()){
            // System.out.println("- Deleting old dist "+outputFile.getName());
            String[]entries = outputFile.list();
            for(String s: entries){
                // System.out.println("- Deleting "+s);
                File currentFile = new File(outputFile.getPath(),s);
                currentFile.delete();
            }
            outputFile.delete();
        }
        outputFile.mkdirs();
        FileOutputStream fos = new FileOutputStream(outputFile.getName()+"/"+appName+"-src.zip", false);
        ZipOutputStream zos = new ZipOutputStream(fos);
        Util.zipDirectory(zos, new File(System.getProperty("user.dir")), null, Boolean.TRUE);
        zos.flush();
        fos.flush();
        zos.close();
        fos.close();
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

}
