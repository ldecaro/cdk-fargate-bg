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
Clone the GitHub project and build the project locally:

```
git clone https://github.com/ldecaro/cdk-fargate-bg.git
cd cdk-fargate-bg
npm install aws-cdk
mvn clean package
```

- **Bootstrapping**

Deploying AWS CDK apps into an AWS environment (a combination of an AWS account and region) requires that you provision resources the AWS CDK needs to perform the deployment. Use the AWS CDK Toolkit's `cdk bootstrap` command to achive that. See the [documentation](https://docs.aws.amazon.com/cdk/v2/guide/bootstrapping.html) for more information. 

When deploying cross-account or cross-region, AWS CodeDeploy requires to use a specific AWS IAM role for the target environment. As a convenience, a script for bootstrapping AWS CodeDeploy is provided. Depending on the use case for deploying the Example microservice, you might need to bootstrap either or both AWS CDK and AWS CodeDeploy. 

First, you should bootstrap AWS CDK in *all the accounts and regions* that are going to be used. At a minimum, if we consider the single account and single region scenario:
```
export CDK_DEPLOY_REGION=us-east-1
export CDK_DEPLOY_ACCOUNT=111111111111
#Configure the AWS CLI with credentials for $CDK_DEPLOY_ACCOUNT
cdk bootstrap $CDK_DEPLOY_ACCOUNT/$CDK_DEPLOY_REGION
```

If you are deploying in *cross-region or cross-account* scenarios, you also need to bootstrap AWS CodeDeploy in the accounts where the microservice is going to be deployed. For that, use the following command:
```
export CDK_DEPLOY_REGION=us-east-2 #your preferred region, IAM is global.
export CDK_DEPLOY_ACCOUNT=222222222222 #the target account
export CDK_DEPLOY_PIPELINE=111111111111 #the toolchain account
#Configure the AWS CLI with credentials for $CDK_DEPLOY_ACCOUNT
./codedeploy-bootstrap.sh
```

In case CDK needs to be bootstrapped in cross-account scenarios, the parameter ```--trust``` is required. For more information, please check the [cdk bootstrap documentation](https://docs.aws.amazon.com/cdk/v2/guide/bootstrapping.html). For convenience, an example is provided below:

```
export TOOLCHAIN_ACCOUNT=111111111111
cdk bootstrap $CDK_DEPLOY_ACCOUNT/$CDK_DEPLOY_REGION --trust $TOOLCHAIN_ACCOUNT --cloudformation-execution-policies arn:aws:iam::aws:policy/AdministratorAccess
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


The CI/CD pipeline is always created with a single deployment stage. The pipeline from the image below was created with two stages, Alpha and Beta In the image below, a stage named *UpdatePipeline* will identify changes and make updates before reaching the Alpha and Beta stages.

The self-mutating capability makes it easy to add manual approval stages or add and remove deployment stages without the need to manually redeploy the toolchain stack. This feature reinforces the notion of a self-contained solution where the toolchain code, application code and application infrastructure code are all maintained inside the git repository.

<img src="/imgs/pipeline-1.png" width=100% >
<img src="/imgs/pipeline-2.png" width=100% >
<img src="/imgs/pipeline-3.png" width=100% >

- **Adding a Gamma stage**: To add another stage (e.g. Gamma) you need to edit two files: ***app.properties*** and ***Toolchain.java***. First, edit the **app.properties** file and add a line representing the new stage (ie. gamma=444444444444/us-east-1). Then, edit the **Toolchain.java**  and add a deployment configuration for the new stage (line 35) like in the example below:

```
    helper.createDeploymentConfig(
        DeploymentConfig.EnvType.GAMMA,
        DeploymentConfig.DEPLOY_LINEAR_10_PERCENT_EVERY_1_MINUTES, 
        props)  
```

  The pipeline will automatically run and self-update once these changes are committed to the repository. 


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
- Destroy Toolchain and Git stacks. The service and api stacks where created by the pipeline, therefore they need to be deleted using cli or the console.

```
#Remove all stacks including the bootstrap of the CodeDeploy
npx cdk destroy --all 
```

#### Optional

The service stacks (alpha and beta) were created by the pipeline, therefore they need to be deleted manually, using the console or the CLI. The name of these stacks follow the standard ```ExampleMicroservice[Alpha|Beta]``` and, depending on the deployment model, they might reside in a different account and region than the toolchain stack.

## Testing 

Once the pipeline reaches the last action of the Deploy stage, the application will become accessible for the first time. You can test the application by using the public URL from the application load balancer. This URL is visible in the Output tab of the CloudFormation stack named `ExampleMicroservice[Alpha|Beta]`. It should look similar to the URL from the application listed in the image below.

<img src="/imgs/app-url.png" width=80%>

Once you access the application on port 80, it will show a hello-world screen with some coloured circles representing the version of the application. At this point, refreshing the page repeatedly will show the different versions of the same application. The Blue and Green versions of this application will appear as in the image below:

<img src="/imgs/blue-app.png" width=50% height=50%><img src="/imgs/green-app.png" width=50% height=50%>

At the same time, in CodeDeploy, it is possible to see in the Deployments menu the details of the deployment like in the image below:

<img src="/imgs/CodeDeployDeployment.png" width=80%>