# Blue/Green Deployments to Amazon ECS using AWS CDK and AWS CodeDeploy

The project deploys a Java-based microservice using a CI/CD pipeline. The pipeline is implemented using the CDK Pipelines construct. The deployment uses AWS CodeDeploy Blue/Green deployment strategy. The microservice can be deployed in **single** or **cross-account** scenarios.

![Architecture](/imgs/arch.png)

The AWS CDK application defines two top-level stacks: 1/ *Toolchain* stack, that deploys the CI/CD pipeline 2/ *Repository* stack, that deploys a Git repository using AWS CodeCommit. The pipeline can deploy the *Example* microservice to a single environment or multiple environments. The **blue** version of the *Example* microservice runtime code is deployed when the Example microservice is deployed the first time in an environment. Onwards, the **green** version of the Example microservice runtime code is deployed using AWS CodeDeploy. The Git repository contains the code of the Example microservice and its toolchain in a self-contained solution.

[Considerations when managing ECS blue/green deployments using CloudFormation](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/blue-green.html#blue-green-considerations) documentation includes the following: _"When managing Amazon ECS blue/green deployments using CloudFormation, you can't include updates to resources that initiate blue/green deployments and updates to other resources in the same stack update"_. The approach used in this project allows to update the Example microservice infrastructure and runtime code in a single commit. To achieve that, the project leverages AWS CodeDeploy's specific [deployment model](https://docs.aws.amazon.com/codedeploy/latest/userguide/deployment-configurations.html#deployment-configuration-ecs) using configuration files to allow updating all resources in the same Git commit.

## Prerequisites 

The project requires the following tools:
* Amazon Corretto 8 - See installation instructions in the [user guide](https://docs.aws.amazon.com/corretto/latest/corretto-11-ug/what-is-corretto-11.html)
* Apache Maven - See [installation instructions](https://maven.apache.org/install.html)
* Docker - See [installation instructions](https://docs.docker.com/engine/install/)
* AWS CLI v2 - See [installation instructions](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)
* Node.js - See [installation instructions](https://nodejs.org/en/download/package-manager/)

## Build 

To build, clone the project from GitHub and build the project locally:

```
git clone https://github.com/ldecaro/cdk-fargate-bg.git
cd cdk-fargate-bg
npm install aws-cdk
mvn clean package
```

Although instructions on this document are specific for Unix environments, project can also be built and executed from a Windows environment.  

- **Bootstrapping**

Deploying AWS CDK apps into an AWS environment (a combination of an AWS account and region) requires that you provision resources the AWS CDK needs to perform the deployment. Use the AWS CDK Toolkit's `cdk bootstrap` command to achive that. See the [documentation](https://docs.aws.amazon.com/cdk/v2/guide/bootstrapping.html) for more information. 

*CDK bootstrap needs to be executed once in each account and region used for running the pipeline or the microservice.* At a minimum, if we consider the use case of a single region and single account deployment, AWS CDK and AWS CodeDeploy will need to be bootstraped once in one account and one region.

To accomplish that, AWS CDK should be bootstrapped using the following commands:
```
#Configure the AWS CLI with credentials for $CDK_DEPLOY_ACCOUNT

export CDK_DEPLOY_REGION=us-east-1
export CDK_DEPLOY_ACCOUNT=111111111111
cdk bootstrap $CDK_DEPLOY_ACCOUNT/$CDK_DEPLOY_REGION
```

In case CDK needs to be bootstrapped in cross-account scenarios, the parameter ```--trust``` is required. For more information, please check the [cdk bootstrap documentation](https://docs.aws.amazon.com/cdk/v2/guide/bootstrapping.html). For convenience, an example is provided below:

```
export TOOLCHAIN_ACCOUNT=111111111111
cdk bootstrap $CDK_DEPLOY_ACCOUNT/$CDK_DEPLOY_REGION --trust $TOOLCHAIN_ACCOUNT --cloudformation-execution-policies arn:aws:iam::aws:policy/AdministratorAccess
```

In addition, AWS CodeDeploy uses a specific AWS IAM role to deploy the microservice. *This role should exist in each account where the microservice is deployed.* As a convenience, a script for bootstrapping AWS CodeDeploy is provided. 

Use the following commands to bootstrap CodeDeploy in one account:
```
#Configure the AWS CLI with credentials for $CDK_DEPLOY_ACCOUNT

export CDK_DEPLOY_REGION=us-east-1 #your preferred region, IAM is global.
export CDK_DEPLOY_ACCOUNT=111111111111 #the target account
export CDK_DEPLOY_PIPELINE=111111111111 #the toolchain account
./codedeploy-bootstrap.sh
```
## Deploy

This approach supports all combinations of deploying the microservice and its toolchain to AWS accounts and Regions. Below you can find a walkthrough for two scenarios: 1/ single account and single region 2/ cross-account and cross-region.

- **Single Account and Single Region**

![Architecture](/imgs/single-account-single-region.png)

 
Deploy the Toolchain and Repository stacks. Deploy the microservice in the same account and region. For other options, please check [DeploymentConfig.java](https://github.com/ldecaro/cdk-fargate-bg/blob/master/src/main/java/com/example/DeploymentConfig.java)
```
export CDK_DEPLOY_ACCOUNT=111111111111
export CDK_DEPLOY_REGION=us-east-1

#Configure AWS CLI to use credentials for $CDK_DEPLOY_ACCOUNT
npx cdk deploy ExampleMicroserviceToolchain --require-approval never
```

- **Cross-Acccount and Cross-Region**

![Architecture](/imgs/cross-account-cross-region.png)


1. Update the method ```getStages``` inside class [com.example.DeploymentConfig.java](https://github.com/ldecaro/cdk-fargate-bg/blob/master/src/main/java/com/example/DeploymentConfig.java) and add or remove stages by changing the elements inside the array of DeploymentConfig:
```
return  Arrays.asList( new DeploymentConfig[]{

  createDeploymentConfig(scope,
      appName,
      "SIT",
      DeploymentConfig.DEPLOY_LINEAR_10_PERCENT_EVERY_1_MINUTES, 
      "111111111111",
      "us-east-1"),

  createDeploymentConfig(scope,
      appName,
      "PreProd",
      DeploymentConfig.DEPLOY_LINEAR_10_PERCENT_EVERY_5_MINUTES, 
      "222222222222",
      "us-east-2"),             
} );
```
2. Build the project
```
mvn clean package
```

3. Deploy the Toolchain and Repository stacks
```
#Configure AWS CLI to use credentials for $TOOLCHAIN_ACCOUNT
npx cdk deploy ExampleMicroserviceToolchain --require-approval never
```

### **The CI/CD Pipeline**


The CI/CD pipeline is always created with a single deployment stage (image below). As a convenience, stages may be added or removed by updating the method ```getStages``` inside class ```com.example.DeploymentConfig```. Please find below an example to create a new stage named ```Prod```:

```
  createDeploymentConfig(scope, #CDK parent construct
      appName, #Name of the microservice
      "Prod", #Name of the stage
      DeploymentConfig.DEPLOY_LINEAR_10_PERCENT_EVERY_1_MINUTES, #deployment configuration
      "111111111111", #target account
      "us-east-1")  #target region
```

The self-mutating capability implemented by CDK Pipelines makes it easy to add manual approval stages or add and remove deployment stages without the need to manually redeploy the toolchain stack. This feature reinforces the notion of a self-contained solution where the toolchain code, application code and application infrastructure code are all maintained inside the git repository. For more information, please check [this](https://aws.amazon.com/pt/blogs/developer/cdk-pipelines-continuous-delivery-for-aws-cdk-applications/) blog about CDK Pipelines.

<img src="/imgs/pipeline-1.png" width=100% >
<img src="/imgs/pipeline-2.png" width=100% >

## Destroy 

### Requisites


- Delete S3 bucket used to store the seed of the git repository. The name will vary, but it will still look similar to the bucket name in the following example:
```
aws s3 rm --recursive s3://ecs-microservice-git-gitseedbucket12345678-1234567890123
aws s3 rb s3://ecs-microservice-git-gitseedbucket12345678-1234567890123
```

- Similarly, delete the S3 bucket used to store the pipeline artifacts. Bucket name should be similar to the one from the example below:
```
aws s3 rm --recursive s3://ecs-microservice-toolchaineartifactsbucket12345678901234567890
aws s3 rb s3://ecs-microservice-toolchaineartifactsbucket12345678901234567890
```
<!--
- Manually delete any images inside the ECR repositories in the accounts and regions where the microservice was deployed. The repository names will follow the pattern ```cdk-hnb659fds-container-assets-ACCOUNT_NUMBER-REGION```
-->
- Destroy Toolchain and Repository stacks. The service and api stacks where created by the pipeline, therefore they need to be deleted using cli or the console.

```
#Remove all stacks including the bootstrap of the CodeDeploy
npx cdk destroy --all 
```

#### Optional

The service stack ```ExampleMicroservicePreProd``` is created by the pipeline, therefore it needs to be manually deleted, using the console or the CLI. Depending on the deployment model, the stack might reside in a different account and region than the toolchain stack.

## Testing 

The application will become accessible for the first time when the pipeline reaches the last action of the Deploy stage. You can test the application by using the public URL from the application load balancer. This URL is visible in the Output tab of the CloudFormation stack named `ExampleMicroservice[Alpha|Beta]` (image below). 

<img src="/imgs/app-url.png" width=80%>

Once you access the application on port 80, it will show a hello-world screen with some coloured circles representing the version of the application. At this point, refreshing the page repeatedly will show the different versions of the same application. The Blue and Green versions of this application will appear as in the image below:

<img src="/imgs/blue-app.png" width=50% height=50%><img src="/imgs/green-app.png" width=50% height=50%>

At the same time, you use the CodeDeploy console to view the deployment details: Sign in to the AWS Management Console and open the CodeDeploy console at https://console.aws.amazon.com/codedeploy. In the navigation pane, expand **Deploy**, and then choose **Deployments**. Click to view the details of the deployment from application **ExampleMicroservice-*** and you will be able to see the deployment status and traffic shifting progress (image below) :

<img src="/imgs/CodeDeployDeployment.png" width=80%>