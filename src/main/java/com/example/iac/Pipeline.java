package com.example.iac;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.zip.ZipOutputStream;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
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
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketEncryption;
import software.amazon.awscdk.services.s3.deployment.BucketDeployment;
import software.amazon.awscdk.services.s3.deployment.ISource;
import software.amazon.awscdk.services.s3.deployment.Source;
import software.constructs.Construct;

public class Pipeline extends Stack {
    
    public Pipeline(Construct scope, String id, String appName, StackProps props) throws Exception{

        super(scope, id, props);
        //creating the configuration files: appspec.yaml, buildspec.yml and taskdef.json

        updateConfigurationFiles(appName, props.getEnv().getAccount(), props.getEnv().getRegion());

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
        // IBucket pipelineBucket   =   Bucket.fromBucketName(scope, "pipeline-bucket", "codepipeline-staging-"+appName);
        // if( pipelineBucket == null){
            Bucket pipelineBucket = Bucket.Builder.create(this, "pipeline-staging-"+appName).bucketName("codepipeline-staging-"+appName).encryption(BucketEncryption.S3_MANAGED).build();
            pipelineBucket.applyRemovalPolicy(RemovalPolicy.DESTROY);
        // }else{
            // System.out.println("Bucket "+pipelineBucket.getBucketName()+" exists!");
        // }

        //pipeline
        Role pipelineRole   =   createPipelineExecutionRole(appName);
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

        Role buildDeployRole    =   createCodeBuildDeployExecutionRole(appName);

        IStage build = pipe.addStage(StageOptions.builder().stageName("Build").build() );
        build.addAction(CodeBuildAction.Builder
                        .create()
                        .input(sourceArtifact)
                        .actionName("Build")
                        .outputs(Arrays.asList(buildArtifact))
                        .type( CodeBuildActionType.BUILD )
                        .variablesNamespace("BuildVariables")
                        .project( createBuildProject( appName, buildDeployRole ) )
                        .build());

        //deploy

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

    private Role createPipelineExecutionRole(final String appName){
        
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
                                ManagedPolicy.fromAwsManagedPolicyName("CloudWatchLogsFullAccess") ))
                            .build();
    }

    private Role createCodeBuildDeployExecutionRole(final String appName){

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

    private void updateConfigurationFiles(String appName, String account, String region){

        Util util   =   new Util();
        //buildspec.yml
        util.updateFile("buildspec-template.yml", "buildspec.yml", new HashMap<String,String>(){{
            put("ACCT_NUMBER", account);
            put("APPLICATION", appName);
            put("REGION", region);
        }});
        //appspec.yml
        util.updateFile("appspec-template.yaml", "appspec.yml", "APPLICATION", "hello-world");
        //taskdef
        util.updateFile("taskdef-template.json", "taskdef.json", "APPLICATION", "hello-world");        
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

}
