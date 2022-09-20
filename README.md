# Blue/Green Deployments to Amazon ECS using AWS CDK and AWS CodeDeploy

The project deploys a Java-based microservice using a CI/CD pipeline. The pipeline is implemented using the CDK Pipelines construct. The deployment uses AWS CodeDeploy Blue/Green deployment strategy. The microservice can be deployed in **single** or **cross-account** scenarios.

![Architecture](/imgs/arch.png)

The AWS CDK application defines two top-level stacks: 1/ *Toolchain* stack, that deploys the CI/CD pipeline 2/ *CodeDeployBootstrap* stack, that bootstraps AWS CodeDeploy in the specified AWS account. The pipeline can deploy the *Example* microservice to a single environment or multiple environments. The **blue** version of the *Example* microservice runtime code is deployed when the Example microservice is deployed the first time in an environment. Onwards, the **green** version of the Example microservice runtime code is deployed using AWS CodeDeploy. This Git repository contains the code of the Example microservice and its toolchain as a self-contained solution.

[Considerations when managing ECS blue/green deployments using CloudFormation](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/blue-green.html#blue-green-considerations) documentation includes the following: _"When managing Amazon ECS blue/green deployments using CloudFormation, you can't include updates to resources that initiate blue/green deployments and updates to other resources in the same stack update"_. The approach used in this project allows to update the Example microservice infrastructure and runtime code in a single commit. To achieve that, the project leverages AWS CodeDeploy's [deployment model](https://docs.aws.amazon.com/codedeploy/latest/userguide/deployment-configurations.html#deployment-configuration-ecs) using configuration files to allow updating all resources in the same Git commit.

## Prerequisites 

The project requires the following tools:
* Amazon Corretto 8 - See installation instructions in the [user guide](https://docs.aws.amazon.com/corretto/latest/corretto-11-ug/what-is-corretto-11.html)
* Apache Maven - See [installation instructions](https://maven.apache.org/install.html)
* Docker - See [installation instructions](https://docs.docker.com/engine/install/)
* AWS CLI v2 - See [installation instructions](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)
* Node.js - See [installation instructions](https://nodejs.org/en/download/package-manager/)

Although instructions in this document are specific for Linux environments, the project can also be built and executed from a Windows environment.  

## Installation

After all prerequisites are met, it usually takes around 10 minutes to follow the instructions below and deploy the AWS CDK Application for the first time. This approach supports all combinations of deploying the microservice and its toolchain to AWS accounts and Regions.

### Push the example project to AWS CodeCommit

To make it easier following the example, the next steps create an AWS CodeCommit repository and use it as source. In this example, I'm authenticating into AWS CodeCommit using [git-remote-codecommit](https://docs.aws.amazon.com/codecommit/latest/userguide/setting-up-git-remote-codecommit.html). Once you have `git-remote-codecommit` configured you can copy and paste the following commands:

```
git clone https://github.com/ldecaro/cdk-fargate-bg.git
cd cdk-fargate-bg
repo_name=$(aws codecommit create-repository \
    --repository-name ExampleMicroservice \
    --output text \
    --query repositoryMetadata.repositoryName)
git remote set-url --push origin codecommit://${repo_name}
git add .
git commit -m "initial import"
git push 
```

## Deploy

This approach supports all combinations of deploying the microservice and its toolchain to AWS accounts and Regions. Below you can find a walkthrough for two scenarios: 1/ single account and single region 2/ cross-account and cross-region.

### Single Account and Single Region

![Architecture](/imgs/single-account-single-region.png)

Let's deploy the Example microservice in single account and single Region scenario. This can be accomplished in 5 steps. If you already executed the cross-account scenario you should [cleanup](#cleanup) first:
**1. Configure environment**

Edit `src/main/java/com/example/BlueGreenConfig.java` and update value of the following 2 properties, making sure they hold the same value, referencing the same account:
```java

    public static final String TOOLCHAIN_ACCOUNT             = "111111111111";
    public static final String MICROSERVICE_ACCOUNT          = "111111111111";
```

**2. Push configuration changes to AWS CodeCommit**

```
git add src/main/java/com/example/BlueGreenConfig.java
git commit -m "initial config"
git push
```

**3. Build and install AWS CDK locally**
```
npm install
mvn clean package
npx cdk synth
```

**4. One-Time Bootstrap**

 - **AWS CDK**

Deploying AWS CDK apps into an AWS environment (a combination of an AWS account and region) requires that you provision resources the AWS CDK needs to perform the deployment. Use the AWS CDK Toolkit's `cdk bootstrap` command to achieve that. See the [documentation](https://docs.aws.amazon.com/cdk/v2/guide/bootstrapping.html) for more information.

*`cdk bootstrap` needs to be executed once in each AWS account and Region used for deployment.* At a minimum, if we consider the use case of a single account and single Region deployment, AWS CDK and AWS CodeDeploy will need to be bootstrapped once in one account and one Region. Below is an example for microservice in account 111111111111 and toolchain in account 111111111111:
```
 npx cdk bootstrap 111111111111/us-east-1
```

 - **AWS CodeDeploy**

In addition, AWS CodeDeploy uses a specific AWS IAM role to perform the blue/green deployment. *This role should exist in each account where the microservice is deployed.* As a convenience, a script for bootstrapping AWS CodeDeploy is provided. 

Use the following command to bootstrap AWS CodeDeploy in one account:
```
./codedeploy-bootstrap.sh 111111111111/us-east-1
```
 
**5. Deploy the Toolchain stack**
It will deploy the microservice in the same account and region as the toolchain:
```
npx cdk deploy ExampleMicroserviceToolchain
```

### Cross-Acccount and Cross-Region

![Architecture](/imgs/cross-account-cross-region.png)

Let's deploy the Example microservice in cross-account and cross-region scenario. This can be accomplished in 5 steps. If you already executed the single account and single region scenario you should [clean up](#cleanup) first:

**1. Configure environment:**

Edit `src/main/java/com/example/BlueGreenConfig.java` and update value of the following 2 properties, making sure they hold the same value, referencing the same account:
```java

    public static final String TOOLCHAIN_ACCOUNT             = "111111111111";
    public static final String TOOLCHAIN_ACCOUNT             = "us-east-1";
    public static final String MICROSERVICE_ACCOUNT          = "222222222222";
    public static final String MICROSERVICE_REGION           = "us-east-2";
```

**2. Push configuration changes to AWS CodeCommit**
```
git add src/main/java/com/example/BlueGreenConfig.java
git commit -m "cross-account config"
git push 
```

**3. Build and install AWS CDK locally**
```
npm install
mvn clean package
npx cdk synth
```

**4. One-Time Bootstrap**

 - **AWS CDK**

Deploying AWS CDK apps into an AWS environment (a combination of an AWS account and region) requires that you provision resources the AWS CDK needs to perform the deployment. Use the AWS CDK Toolkit's `cdk bootstrap` command to achieve that. See the [documentation](https://docs.aws.amazon.com/cdk/v2/guide/bootstrapping.html) for more information.

*`cdk bootstrap` needs to be executed once in each AWS account and Region used for deployment.* At a minimum, if we consider the use case of a cross-account and cross-region deployment, AWS CDK will need to be bootstrapped once in each account and each region. 

For cross-account scenarios, the parameter ```--trust``` is required. For more information, please see the [AWS CDK Bootstrapping documentation](https://docs.aws.amazon.com/cdk/v2/guide/bootstrapping.html). Below is an example for microservice in account 222222222222 and toolchain in account 111111111111:
```
 npx cdk bootstrap 111111111111/us-east-1
```
```
 #make sure your aws credentials are pointing to account 222222222222
 npx cdk bootstrap 222222222222/us-east-2 --trust 111111111111
```

 - **AWS CodeDeploy**

In addition, AWS CodeDeploy uses a specific AWS IAM role to perform the blue/green deployment. *This role should exist in each account where the microservice is deployed.* As a convenience, a script for bootstrapping AWS CodeDeploy is provided. 

Use the following commands to bootstrap AWS CodeDeploy in accounts 111111111111 & 222222222222:
```
./codedeploy-bootstrap.sh 111111111111/us-east-1
```
```
#make sure your aws credentials are pointing to account 222222222222
./codedeploy-bootstrap.sh 222222222222/us-east-1 --trust 111111111111 --cloudformation-execution-policies arn:aws:iam::aws:policy/AdministratorAccess
```

**5. Deploy the Toolchain stack**
```
npx cdk deploy ExampleMicroserviceToolchain
```
### **The CI/CD Pipeline**

The pipeline is deployed by a Construct named `BlueGreenPipeline`. As a convenience, the number of stages in the pipeline is dynamic therefore and it can support a different number of use cases.

```java
new BlueGreenPipeline(
    this,
    "BlueGreenPipeline", 
    appName, 
    gitRepo, 
    Config.getStages(
        scope, 
        appName));
```

By default, the CI/CD pipeline is created with a single deployment stage (PreProd). The class `com.example.BlueGreenConfig` contains a method named `getStages` that can be updated to add or remove stages. Please find below an example to add a new stage named ```Prod```:

```java
static List<BlueGreenConfig> getStages(final Construct scope, final String appName){

    return  Arrays.asList( new BlueGreenConfig[]{

        BlueGreenConfig.createDeploymentConfig(scope,
            appName,
            "PreProd",
            BlueGreenConfig.DEPLOY_LINEAR_10_PERCENT_EVERY_3_MINUTES,
            BlueGreenConfig.MICROSERVICE_ACCOUNT,
            BlueGreenConfig.MICROSERVICE_REGION)

        //add more stages to your pipeline here    
        BlueGreenConfig.createDeploymentConfig(scope,
            appName,
            "Prod",
            BlueGreenConfig.DEPLOY_LINEAR_10_PERCENT_EVERY_3_MINUTES,
            BlueGreenConfig.MICROSERVICE_ACCOUNT,
            BlueGreenConfig.MICROSERVICE_REGION)   

        //add a DR stage.                 
        BlueGreenConfig.createDeploymentConfig(scope,
            appName,
            "DR",
            BlueGreenConfig.DEPLOY_LINEAR_10_PERCENT_EVERY_3_MINUTES,
            BlueGreenConfig.MICROSERVICE_ACCOUNT,
            "us-east-2")               
    } );
}

```

The self-mutating capability implemented by CDK Pipelines makes it easy to add manual approval stages or add and remove deployment stages without the need to manually redeploy the toolchain stack. This feature reinforces the notion of a self-contained solution where the toolchain code, microservice infrastructure code and microservice runtime code are all maintained inside the same Git repository. For more information, please check [this](https://aws.amazon.com/pt/blogs/developer/cdk-pipelines-continuous-delivery-for-aws-cdk-applications/) blog about CDK Pipelines.

<img src="/imgs/pipeline-1.png" width=100% >
<img src="/imgs/pipeline-2.png" width=100% >

## <a name="cleanup"></a> Clean up 


- Clean the S3 bucket used to store the pipeline artifacts. Bucket name should be similar to the one from the example below:
```
aws s3 rm --recursive s3://examplemicroservicetoolc-examplemicroservicecodep-6cb6ua606lwi
```
<!--
- Manually delete any images inside the ECR repositories in the accounts and regions where the microservice was deployed. The repository names will follow the pattern ```cdk-hnb659fds-container-assets-ACCOUNT_NUMBER-REGION```
-->
- Destroy the stacks:

```
#Remove all stacks including CodeDeployBootstrap
npx cdk destroy "**"  # Includes the microservice deployments by the pipeline
```
If, for some reason, the destroy fails, just wait for it to finish and try again.

- Delete the repository:    

```
aws codecommit delete-repository --repository-name ExampleMicroservice
```
## Testing 

Once the deployment of the blue task is complete, you can find the public URL of the application load balancer in the Outputs tab of the CloudFormation stack named `ExampleMicroservicePreProd` (image below) to test the application. If you open the application before the green deployment is completed, you can see the rollout live. 

<img src="/imgs/app-url.png" width=80%>

Once acessed, the application will show a hello-world screen with some coloured circles representing the version of the application. At this point, refreshing the page repeatedly will show the different versions of the same application. The Blue and Green versions of this application will appear as in the image below:

<img src="/imgs/blue-app.png" width=50% height=50%><img src="/imgs/green-app.png" width=50% height=50%>

At the same time, you can view the deployment details using the console of the CodeDeploy: for that, Sign in to the AWS Management Console and open the CodeDeploy console at https://console.aws.amazon.com/codedeploy. In the navigation pane, expand **Deploy**, and then choose **Deployments**. Click to view the details of the deployment from application **ExampleMicroservice-*** and you will be able to see the deployment status and traffic shifting progress (image below) :

<img src="/imgs/CodeDeployDeployment.png" width=80%>