package com.example.demo;

import java.util.Arrays;

import com.example.demo.toolchain.infrastructure.ContinuousDeployment;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.SymlinkFollowMode;
import software.amazon.awscdk.cxapi.CloudAssembly;
import software.amazon.awscdk.services.codedeploy.EcsDeploymentConfig;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.s3.assets.AssetOptions;
import software.amazon.awscdk.services.servicecatalog.CloudFormationProduct;
import software.amazon.awscdk.services.servicecatalog.CloudFormationProductVersion;
import software.amazon.awscdk.services.servicecatalog.CloudFormationTemplate;
import software.amazon.awscdk.services.servicecatalog.Portfolio;
import software.constructs.Construct;

public class DemoServiceCatalog {

    private static final String TOOLCHAIN_ACCOUNT = "742584497250";
    private static final String TOOLCHAIN_REGION = "us-east-1";

    public static final String COMPONENT_ACCOUNT = "279211433385";
    public static final String COMPONENT_REGION = "us-east-1";    

    public static void main(String args[]){

        App app = new App();

        // note that the ContinuousDeployment build() method encapsulates
        // implementaton details for adding role permissions in cross-account scenarios
        ContinuousDeployment.Builder.create(app, Constants.APP_NAME+"Pipeline")
                .stackProperties(StackProps.builder()
                        .env(Environment.builder()
                                .account(DemoServiceCatalog.TOOLCHAIN_ACCOUNT)
                                .region(DemoServiceCatalog.TOOLCHAIN_REGION)
                                .build())
                        .build())
                .setGitRepo(Demo.CODECOMMIT_REPO)
                .setGitBranch(Demo.CODECOMMIT_BRANCH)
                .addStage(
                        "UAT",
                        EcsDeploymentConfig.CANARY_10_PERCENT_5_MINUTES,
                        Environment.builder()
                                .account(DemoServiceCatalog.COMPONENT_ACCOUNT)
                                .region(Demo.COMPONENT_REGION)
                                .build())
                .build();

        CloudAssembly ca = app.synth();

        App app2 = new App();

        new ServiceStack(app2, 
            "ExampleServiceStack", 
            ca,
            StackProps.builder()
                .env(Environment.builder()
                        .account(DemoServiceCatalog.TOOLCHAIN_ACCOUNT)
                        .region(DemoServiceCatalog.TOOLCHAIN_REGION)
                        .build())
            .build());

        app2.synth();
    }

    public static class ServiceStack extends Stack{
        
        public ServiceStack(Construct scope, String id, CloudAssembly ca, StackProps props){
            
            super(scope, id, props);

            System.out.println("Starting the ExampleServiceCatalog");

            Portfolio portfolio = Portfolio.Builder.create(this, "MyPortfolio")
            .displayName("My service portfolio")
            .description("Portfolio with ECS products")
            .providerName("AWS Solution Architecture")
            .build();

            portfolio.giveAccessToRole(Role.fromRoleName(
                this, 
                "MyPortfolioRole", 
                "AWSReservedSSO_AdministratorAccess_78a27d5b4986dc4e"));


            portfolio.addProduct(CloudFormationProduct.Builder.create(this, "MyProduct")
            .productName("My Cross-Account Blue/Green Pipeline")
            .owner("Example.com")
            
            .productVersions(Arrays.asList(CloudFormationProductVersion.builder()
                    .productVersionName("v1")
                    .description("This pipeline is configured to deploy the contents of the repository and branch to the account "+DemoServiceCatalog.TOOLCHAIN_ACCOUNT+" and region "+DemoServiceCatalog.TOOLCHAIN_REGION)
                    .cloudFormationTemplate(CloudFormationTemplate.fromAsset(ca.getStackByName(Constants.APP_NAME+"Pipeline").getTemplateFullPath(), AssetOptions.builder().followSymlinks(SymlinkFollowMode.ALWAYS).build()))
                    .build()))
            .build());     
        }
    }
}