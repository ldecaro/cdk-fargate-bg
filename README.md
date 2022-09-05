# Blue/Green Deployments to Amazon ECS using AWS CDK and AWS CodeDeploy

The project deploys a Java-based microservice using a CI/CD pipeline. The pipeline is implemented using the CDK Pipelines construct. The deployment uses AWS CodeDeploy Blue/Green deployment strategy. The microservice can be deployed in **single** or **cross-account** scenarios.

![Architecture](/imgs/arch.png)

The AWS CDK application defines two top-level stacks: 1/ *Toolchain* stack, that deploys the CI/CD pipeline 2/ *Repository* stack, that deploys a Git repository using AWS CodeCommit. The pipeline can deploy the *Example* microservice to a single environment or multiple environments. The **blue** version of the *Example* microservice runtime code is deployed when the Example microservice is deployed the first time in an environment. Onwards, the **green** version of the Example microservice runtime code is deployed using AWS CodeDeploy. This Git repository contains the code of the Example microservice and its toolchain in a self-contained solution.

[Considerations when managing ECS blue/green deployments using CloudFormation](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/blue-green.html#blue-green-considerations) documentation includes the following: _"When managing Amazon ECS blue/green deployments using CloudFormation, you can't include updates to resources that initiate blue/green deployments and updates to other resources in the same stack update"_. The approach used in this project allows to update the Example microservice infrastructure and runtime code in a single commit. To achieve that, the project leverages AWS CodeDeploy's specific [deployment model](https://docs.aws.amazon.com/codedeploy/latest/userguide/deployment-configurations.html#deployment-configuration-ecs) using configuration files to allow updating all resources in the same Git commit.

## Prerequisites 

The project requires the following tools:
* Amazon Corretto 8 - See installation instructions in the [user guide](https://docs.aws.amazon.com/corretto/latest/corretto-11-ug/what-is-corretto-11.html)
* Apache Maven - See [installation instructions](https://maven.apache.org/install.html)
* Docker - See [installation instructions](https://docs.docker.com/engine/install/)
* AWS CLI v2 - See [installation instructions](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)
* Node.js - See [installation instructions](https://nodejs.org/en/download/package-manager/)

Although instructions on this document are specific for Linux environments, project can also be built and executed from a Windows environment.  

## Clone and mirror the example to AWS CodeComit

To make it easier following the example, create an empty AWS CodeCommit repository and use it as source code repository for the pipeline. In this example, I'm authenticating into AWS CodeCommit using [git credentials](https://docs.aws.amazon.com/codecommit/latest/userguide/setting-up-gc.html). Once you have your credentials set you can copy and paste the following commands:

```
git clone --mirror https://github.com/ldecaro/cdk-fargate-bg.git
cd cdk-fargate-bg.git

export REPO_URL=$(aws codecommit create-repository --repository-name ExampleMicroservice --output text --query repositoryMetadata.cloneUrlHttp)
git remote set-url --push origin $REPO_URL
git push --mirror
cd ..
rm -rf cdk-fargate-bg.git
```
## Clone the environment from CodeCommit:

```
git clone $REPO_URL
cd ExampleMicroservice
```


## Configure environment

Edit `src/main/java/com/example/Config.java` and validate the value of the following 5 properties:
```
    public static final String TOOLCHAIN_REGION      = "us-east-1";
    public static final String TOOLCHAIN_ACCOUNT     = "111111111111";
    public static final String APP_NAME              = "ExampleMicroservice";
    public static final String CODECOMMIT_REPO       = Config.APP_NAME;
    public static final String CODECOMMIT_BRANCH     = "master";
```

## Upload codebase into AWSCodeCommit

I'm considering  most people will be running this in sandbox accounts. Therefore, I decided to use AWSCodeCommit to host the repository. If you need details to configure authentication into CodeCommit please refer to [this](https://docs.aws.amazon.com/codecommit/latest/userguide/setting-up.html) tutorial.
```
git add src/main/java/com/example/Config.java
git commit -m "my blue/green pipeline"
git push
```

## Build and install AWS CDK locally
```
npm install aws-cdk@2.31.1
mvn clean package
cdk ls
```

## Bootstrap

Deploying AWS CDK apps into an AWS environment (a combination of an AWS account and region) requires that you provision resources the AWS CDK needs to perform the deployment. Use the AWS CDK Toolkit's `cdk bootstrap` command to achieve that. See the [documentation](https://docs.aws.amazon.com/cdk/v2/guide/bootstrapping.html) for more information.

*`cdk bootstrap` needs to be executed once in each AWS account and Region used for deployment.* At a minimum, if we consider the use case of a single region and single account deployment, AWS CDK and AWS CodeDeploy will need to be bootstrapped once in one account and one region.

To accomplish that, use the following commands:
```
export MICROSERVICE_ACCOUNT=111111111111
export MICROSERVICE_REGION=us-east-1
cdk bootstrap $MICROSERVICE_ACCOUNT/$MICROSERVICE_REGION
```

In case the AWS CDK needs to be bootstrapped in cross-account scenarios, the parameter ```--trust``` is required. For more information, please see the [AWS CDK Bootstrapping documentation](https://docs.aws.amazon.com/cdk/v2/guide/bootstrapping.html). For convenience, an example is provided below:

```
export TOOLCHAIN_ACCOUNT=111111111111
cdk bootstrap $MICROSERVICE_ACCOUNT/$MICROSERVICE_REGION --trust TOOLCHAIN_ACCOUNT
```

In addition, AWS CodeDeploy uses a specific AWS IAM role to perform the blue/green deployment. *This role should exist in each account where the microservice is deployed.* As a convenience, a script for bootstrapping AWS CodeDeploy is provided. 

Use the following command to bootstrap CodeDeploy in one account:
```
./codedeploy-bootstrap.sh $TOOLCHAIN_ACCOUNT $MICROSERVICE_ACCOUNT/$MICROSERVICE_REGION
```
## Deploy

This approach supports all combinations of deploying the microservice and its toolchain to AWS accounts and Regions. Below you can find a walkthrough for two scenarios: 1/ single account and single region 2/ cross-account and cross-region.

- **Single Account and Single Region**

![Architecture](/imgs/single-account-single-region.png)

 
Deploy the Toolchain and Repository stacks. Deploy the microservice in the same account and region. For other options, please check [DeploymentConfig.java](https://github.com/ldecaro/cdk-fargate-bg/blob/master/src/main/java/com/example/DeploymentConfig.java)
```
npx cdk deploy ExampleMicroserviceToolchain --require-approval never
```

- **Cross-Acccount and Cross-Region**

![Architecture](/imgs/cross-account-cross-region.png)


1. Update the method ```getStages``` inside class `src/main/java/com/example/Config.java` and add or remove stages as needed::
```
return  Arrays.asList( new DeploymentConfig[]{

  Config.createDeploymentConfig(scope,
      appName,
      "PreProd",
      DeploymentConfig.DEPLOY_LINEAR_10_PERCENT_EVERY_3_MINUTES,
      Config.TOOLCHAIN_ACCOUNT,
      Config.TOOLCHAIN_REGION),

  Config.createDeploymentConfig(scope,
      appName,
      "Prod",
      DeploymentConfig.DEPLOY_LINEAR_10_PERCENT_EVERY_3_MINUTES,
      222222222222,
      Config.TOOLCHAIN_REGION)            
} );
```
2. Build the project
```
mvn clean package
```

3. Commit into repository:
```
git add src/main/java/com/example/Config.java
git commit -m "cross-account blue/green pipeline"
git push 
```

4. Deploy the Toolchain and Repository stacks
```
npx cdk deploy ExampleMicroserviceToolchain --require-approval never
```

### **The CI/CD Pipeline**

The pipeline is deployed by a Construct named `BlueGreenPipeline`. As a convenience, the number of stages is dynamic and can be customized according to the requirements.

```
new BlueGreenPipeline(
    this,
    "BlueGreenPipeline", 
    appName, 
    gitRepo, 
    Config.getStages(
        scope, 
        appName));
```

By default, the CI/CD pipeline is created with a single deployment stage (PreProd). The class `com.example.Config` contains a method named `getStages` that can be updated to add or remove stages. Please find below an example to add a new stage named ```Prod```:

```
Config.createDeploymentConfig(scope,
    appName,
    "Prod",
    DeploymentConfig.DEPLOY_LINEAR_10_PERCENT_EVERY_3_MINUTES,
    Config.TOOLCHAIN_ACCOUNT,
    Config.TOOLCHAIN_REGION)
```

The self-mutating capability implemented by CDK Pipelines makes it easy to add manual approval stages or add and remove deployment stages without the need to manually redeploy the toolchain stack. This feature reinforces the notion of a self-contained solution where the toolchain code, application code and application infrastructure code are all maintained inside the git repository. For more information, please check [this](https://aws.amazon.com/pt/blogs/developer/cdk-pipelines-continuous-delivery-for-aws-cdk-applications/) blog about CDK Pipelines.

<img src="/imgs/pipeline-1.png" width=100% >
<img src="/imgs/pipeline-2.png" width=100% >

## Destroy 

### Requisites

- Delete the S3 bucket used to store the pipeline artifacts. Bucket name should be similar to the one from the example below:
```
aws s3 rm --recursive s3://examplemicroservicetoolc-examplemicroservicecodep-13r76jz2oozhx
aws s3 rb examplemicroservicetoolc-examplemicroservicecodep-13r76jz2oozhx
```
<!--
- Manually delete any images inside the ECR repositories in the accounts and regions where the microservice was deployed. The repository names will follow the pattern ```cdk-hnb659fds-container-assets-ACCOUNT_NUMBER-REGION```
-->
- Destroy the stacks and tje repository:

```
#Remove all stacks including the bootstrap of the CodeDeploy
npx cdk destroy "**"  # Includes the microservice deployments by the pipeline
aws codecommit delete-repository --repository-name ExampleMicroservice
```
## Testing 

The application will become accessible for the first time when the pipeline reaches the last action of the Deploy stage. You can test the application by using the public URL from the application load balancer. This URL is visible in the Output tab of the CloudFormation stack named `ExampleMicroservice[Alpha|Beta]` (image below). 

<img src="/imgs/app-url.png" width=80%>

Once you access the application on port 80, it will show a hello-world screen with some coloured circles representing the version of the application. At this point, refreshing the page repeatedly will show the different versions of the same application. The Blue and Green versions of this application will appear as in the image below:

<img src="/imgs/blue-app.png" width=50% height=50%><img src="/imgs/green-app.png" width=50% height=50%>

At the same time, you use the CodeDeploy console to view the deployment details: Sign in to the AWS Management Console and open the CodeDeploy console at https://console.aws.amazon.com/codedeploy. In the navigation pane, expand **Deploy**, and then choose **Deployments**. Click to view the details of the deployment from application **ExampleMicroservice-*** and you will be able to see the deployment status and traffic shifting progress (image below) :

<img src="/imgs/CodeDeployDeployment.png" width=80%>