package com.example.cdk;

import java.util.Arrays;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.codecommit.CfnRepository;
import software.amazon.awscdk.services.codecommit.CfnRepository.CodeProperty;
import software.amazon.awscdk.services.codecommit.CfnRepository.S3Property;
import software.amazon.awscdk.services.codecommit.IRepository;
import software.amazon.awscdk.services.codecommit.Repository;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketEncryption;
import software.amazon.awscdk.services.s3.deployment.BucketDeployment;
import software.amazon.awscdk.services.s3.deployment.ISource;
import software.amazon.awscdk.services.s3.deployment.Source;
import software.constructs.Construct;

public class Git extends Stack {
    
    private IRepository gitRepo =   null;
    
    public Git(Construct scope, String id, String appName, final Boolean IS_CREATING, StackProps props){
        
        super(scope, id, props);

        Bucket gitSeed = Bucket.Builder.create(this, "GitSeedBucket")
                                        .encryption(BucketEncryption.S3_MANAGED)
                                        .removalPolicy(RemovalPolicy.DESTROY)
                                        .build();         

        BucketDeployment s3Deployment   =   null;
        if( IS_CREATING ){
            //we are deploying the pipeline
            s3Deployment    =   BucketDeployment.Builder.create(this, "GitSeedBucketDeployment")
                .sources(Arrays.asList(new ISource[]{ Source.asset("./dist") }))
                .destinationBucket(gitSeed)
                .retainOnDelete(Boolean.TRUE)
                .build();                                        
        }

		IRepository gitRepo = Repository.Builder.create(this, "CodeCommitRepository")
            .repositoryName(appName)
            .build();
							
		((CfnRepository)gitRepo
            .getNode()
            .getDefaultChild())
            .setCode(CodeProperty.builder()
                .s3(S3Property
                    .builder()
                    .bucket(gitSeed.getBucketName())
                    .key(appName+"-src.zip")
                    .build())
                .build());

        if( IS_CREATING ){
            gitRepo.getNode().addDependency(s3Deployment);
        }

        this.gitRepo    =   gitRepo;
        CfnOutput.Builder.create(this, "RepositoryName")
            .description("Name of the git repository for project "+appName)
            .value(gitRepo.getRepositoryName())
            .build();
    }

    public IRepository getGitRepository(){
        return gitRepo;
    }
}
