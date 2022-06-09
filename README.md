# Blue/Green Deployments to Amazon ECS using AWS CDK and AWS CodeDeploy

A CI/CD pipeline using CDK Pipelines. Deploys a Java based microservice with AWS CodeDeploy using Blue/Green or Canary. Works in **single** or **cross-account** scenario.

![Architecture](/imgs/general.png)

A CDK *Toolchain* stack deploys a self-mutating pipeline and a *Repository* stack deploys a git repository using AWS CodeCommit. The pipeline runs and deploys the *Api* stack in different stages (Alpha and Beta). The **blue** version of the application is deployed with the Api stack using AWS CloudFormation. The **green** version of the application is deployed using AWS CodeDeploy.

This strategy will allow updates to the Api and infrastructure in a single commit. The Self-Mutating Pipeline makes it easy to add or remove stages that can deploy the different versions of the same application in a single or cross account scenario. When a change to the pipeline is identified, it self-mutates during the *Update Pipeline* stage, prior to executing the Deploy stages.

The Repository stack deploys a git repository that hosts a Java based microservice and CDK code that can be changed to update the Toolchain or the Api.
 <!-- I created a tarball with a single HTML container running HTTPD with a total size around 20KB but, for this reference application, I decided to create a nginx container running a single page Blue app. The size of the container of the Blue app is around 20MB and this should not impact the time the pipeline takes to execute. If this is a problem for you, you can easily create a single HTML page container with around 20KB of size and change the Infrastructure stack to load the tarball every time it executes. -->

This reference pipeline can be particularly useful in cases when:

- Changes need to be executed in the infrastructure and in the application in the same commit without external coordination of events. This is particularly useful in applications with an extense integration phase;
- Infrastructure ends up being more than just the ECS and the solution using Hooks might not be a fit for your use case;
- There is a need to use CDK with a nested stack;
- There is a requirement to use one of the features listed under [considerations](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/blue-green.html#blue-green-considerations) when using CloudFormation Hooks.

***Why CodeDeploy?***

Deploying containers at an enterprise level might require these capabilities:

- Stop an ongoing deployment;
- Stop and Rollback an ongoing deployment;
- Audit an old deployment;
- Implement governance and control who might be able to rollback a deployment;
- Rollback to an old version of the same application or microservice. The ability to use the *Redeploy* button might be useful at some point in your journey (specially if a regulator asks how you rollback a version and who has access to do that).


![Architecture](/imgs/arch.png)

## Installation 

Requires Java8, Maven, AWS CLI and CDK

- MacOs

```
# brew tap adoptopenjdk/openjdk
# brew install --cask adoptopenjdk8
# brew install maven
# brew install awscli
# brew install aws-cdk
# brew install docker
```

- Cloud9

```
# sudo wget https://repos.fedorapeople.org/repos/dchen/apache-maven/epel-apache-maven.repo -O /etc/yum.repos.d/epel-apache-maven.repo
# sudo sed -i s/\$releasever/6/g /etc/yum.repos.d/epel-apache-maven.repo
# sudo yum install -y apache-maven

```

## Build 
Download the github project and build project locally:

```
# git clone https://github.com/ldecaro/cdk-fargate-bg.git
# cd cdk-fargate-bg
# mvn clean package
```
## Deploy

The pipeline can be deployed in the following scenarios in four simple steps:

- **Single Account**

![Architecture](/imgs/single-account.png)

First, we need to bootstrap CDK in the account where the Toolchain and Api will be deployed. **Even if the CDK has already been bootstrapped in the account we need to start by running the script below.**


1. Define account
```
export TOOLCHAIN_ACCT=111111111111
```

2. Bootstrap account
```
./cdk-bootstrap-deploy-to.sh $TOOLCHAIN_ACCT us-east-1
```
3. Configure the parameter file with the correct environment variables
```
echo "toolchain=$TOOLCHAIN_ACCT/us-east-1" >> app.properties
echo "alpha=$TOOLCHAIN_ACCT/us-east-1" >> app.properties
echo "beta=$TOOLCHAIN_ACCT/us-east-1" >> app.properties
```
4. Deploy the Toolchain and Repository stacks
```
cdk deploy ecs-microservice-toolchain --require-approval never
```

- **Cross-Acccount**

![Architecture](/imgs/cross-account.png)

1. Define accounts
```
export TOOLCHAIN_ACCT=111111111111
export ALPHA_ACCT=222222222222
export BETA_ACCT=333333333333
```

2. Bootstrap accounts and add the AWS CodeDeploy cross-account role in the remote accounts with the correct trust

```
./cdk-bootstrap-deploy-to.sh $TOOLCHAIN_ACCT us-east-1
./cdk-bootstrap-deploy-to.sh $ALPHA_ACCT us-east-1 --trust $TOOLCHAIN_ACCT
./cdk-bootstrap-deploy-to.sh $BETA_ACCT us-east-1 --trust $TOOLCHAIN_ACCT
```

3. Configure the parameter file with the correct environment variables
```
echo "toolchain=$TOOLCHAIN_ACCT/us-east-1" >> app.properties
echo "alpha=$ALPHA_ACCT/us-east-1" >> app.properties
echo "beta=$BETA_ACCT/us-east-1" >> app.properties
```

4. Deploy the Toolchain and Repository stacks
```
#cross-account with onle Beta stage in the remote account
cdk deploy ecs-microservice-toolchain --require-approval never
```

<!-- It creates an ECS Cluster, deploys the service using ECR, creates the CodeCommit repository and a reference Pipeline. Runs the Pipeline to execute a BlueGreen deployment using deployment configuration: *CodeDeployDefault.ECSLinear10PercentEvery1Minutes*.

When the pipeline is deployed it will build the project and, in the Deploy, stage it will configure CodeDeploy and execute two stacks in parallel: ECSStack and ServiceAssetStack. ECSStack deploys the ECS Fargate Infrastructure including the Blue Application and ServiceAssetStack deploys the Green application in ECR using CDK's DockerImageAsset. Once the ECSStack is deployed and Green application is uploaded into ECR, CodeDeploy is invoked and Blue/Green Deployment takes place. -->

- **Pipeline**

By default, the resulting pipeline will be created with two deployment stages: Alpha and Beta. This pipeline can be modified so that new deployment stages can easily be added or removed. For convenience, the Gamma stage has already been coded and, to enable it, uncomment code inside the app.properties file.

<img src="/imgs/pipeline-1.png" width=100% >
<img src="/imgs/pipeline-2.png" width=100% >
<img src="/imgs/pipeline-3.png" width=100% >

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

<!-- It's the pipeline who deploys the Api stack. Destroying using the CDK destroy won't destroy the Api and service stacks, therefore it needs to be manually deleted directly from AWS CloudFormation. In case of a multi-account deployment, the service and api stacks need to be deleted from the _target account_.  This also prevents the ECS Fargate service from being accidentally deleted. All other infrastructure stacks (git, toolchain, service) can be destroyed using a single command: -->

- Destroy Toolchain and Git stacks. The service and api stacks where created by the pipeline, thererfoe they need to be deleted using cli or the console.

```
cdk destroy --all
```

## Testing 

When the pipeline reaches the latest action of the Deploy stage, the application will become acessible for the first time. You can test the application by using the public URL from the application load balancer. This URL is visible in the Output tab of the CloudFormation stack named `ecs-microservice-infra-[alpha|beta]`. Once you access the application on port 80, it will show a hello-world screen. In CodeDeploy, you can see the details of the deployment listed in the Deployments menu. The image below shows how it looks like:

<img src="/imgs/CodeDeployDeployment.png" width=80%>

At this point, refreshing the page repeatedly will show the different versions of the same application. The Green application will appear. It looks like the image below:

<img src="/imgs/blue-app.png" width=50% height=50%><img src="/imgs/green-app.png" width=50% height=50%>

