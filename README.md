# Blue/Green Deployments to Amazon ECS using AWS CDK and AWS CodeDeploy

An implementation of a basic microservice running in ECS Fargate deployed using AWS CodePipeline, AWS CodeBuild, AWS CodeDeploy using a Blue/Green deployment type in a **single** or **cross-account** scenario.

![Architecture](/imgs/stacks.png)

It uses a set of 4 stacks: Git, Pipeline, Infrastructure and Application. To achieve the desired results, it implements an *Inversion-of-Control (IoC)*, taking away the ownership from the Infrastructure stack to deploy new versions of the app to make AWS CodeDeploy responsible for deploying the Green ECS Fargate task. 

As a result, it allows updates to the Infrastructure stack and to the application all in the same commit. This code can be extended to support the deployment of other infrastructure components if necessary. In this case, the Deploy stage of the pipeline can be updated to deploy another stack or custom components can be added into the existing Infrastructure stack.

This application uses a multi-stack approach, what can be considered to be more flexible than the approach using CloudFormation Hooks to implement a similar use case. It adds the ability to *Stop a Deployment* or *Stop and Rollback Deployment*, available directly from the console of AWS CodeDeploy.

The Inversion-of-Control (IoC) is implemented with the use of CodeDeploy to deploy the Green application and subsequent versions of it. This is enabled with the use of a Self-Mutating CDK Pipeline that identifies changes in the infrastructure and application associated with the pipeline. When this happens, it self-mutates to deploy these changes prior to executing the Blue/Green deployment. 

As a convenience, the Git stack is triggered as a dependency from the Pipeline stack. This creates a self-contained solution for deploying a new microservice from scratch. The Pipeline runs and builds the application, self-mutates and executes the infrastructure stack. When the infrastructure (ECS) is created, it deploys the Blue Application. We must make sure the Blue application is never changed from this point forward. This is a requirement, because we want to implement an IoC and have CodeDeploy deploying versions of the same application. Initially, I created a tarball with a single HTML container running HTTPD with a total size around 20KB but, for this reference application, I decided to create a nginx container running a single page Blue app. The size of the container of the Blue app is around 20MB and this should not impact the time the pipeline takes to execute. If this is a problem for you, you can easily create a single HTML page container with around 20KB of size and change the Infrastructure stack to load the tarball every time it executes.

This reference application can be particularly useful in cases when:

- Application has too many tests, including funcional tests and you need the pipeline to run through all these tests just to make a change into the underlying infrastructure; Using the approach from this reference application changes can be executed in the infra and in the application in the same commit avoiding the requirement for external coordination of events;
- Your infrastructure ends up being more than just the ECS and the solution using Hooks might not be a fit for your use case; 
- You want to keep changes to the infrastructure and application within the same "package" or commit;
- You need to use CDK with a nested stack;
- You have any other requirement that is listed under the [considerations](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/blue-green.html#blue-green-considerations) for implementing Blue/Green using CloudFormation Hooks;

***Why CodeDeploy?***

If you are deploying containers at an enterprise level you might need one of these capabilities:

- Stop an ongoing deployment;
- Stop and Rollback an ongoing deployment;
- Audit an old deployment;
- Implement governance and control who might be able to rollback a deployment;
- Rollback to an old version of the same application or microservice. The ability to use the *Redeploy* button might be useful at some point in your journey.


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

Deploy Blue/Green fargate microservice:

- Single Account

```
cdk bootstrap
cdk deploy ecs-microservice-pipeline --require-approval never
```

- Cross-Acccount
```
cdk bootstrap aws://987654321098/us-east-1
cdk bootstrap aws://123456789012/us-east-1

#cross-account with onle Beta stage in the remote account
cdk deploy ecs-microservice-pipeline -c beta=12345678910/us-east-1 --require-approval never

#cross-account  with alpha and beta in remote accounts
cdk deploy ecs-microservice-pipeline -c alpha=12346787901/us-east-1 -c beta=987654321098/us-east-1 --require-approval never
```

It creates an ECS Cluster, deploys the microservice using ECR, creates the CodeCommit repository and a minimal Pipeline. Runs the Pipeline to execute a BlueGreen deployment using deployment configuration: *CodeDeployDefault.ECSLinear10PercentEvery1Minutes*.

When the pipeline is deployed it will build the project and, in the Deploy, stage it will configure CodeDeploy and execute two stacks in parallel: ECSStack and ServiceAssetStack. ECSStack deploys the ECS Fargate Infrastructure including the Blue Application and ServiceAssetStack deploys the Green application in ECR using CDK's DockerImageAsset. Once the ECSStack is deployed and Green application is uploaded into ECR, CodeDeploy is invoked and Blue/Green Deployment takes place.

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
aws s3 rm --recursive s3://ecs-microservice-pipelineeartifactsbucket12345678901234567890
aws s3 rb s3://ecs-microservice-pipelineeartifactsbucket12345678901234567890
```

The pipeline creates the ECS Stack and not CDK directly. Destroying using the CDK won't destroy the ECSStack therefore it needs to be manually deleted directly from AWS CloudFormation. In case of a multi-account deployment, it needs to be deleted from the _target account_.  This also prevents the ECS Fargate service from being accidentally deleted. All other infrastructure stacks (git, pipeline, service) can be destroyed using a single command:

```
cdk destroy --all
```

## Testing 

When the pipeline reaches the latest action of the Deploy stage, the application will become acessible for the first time. You can test the application by using the public URL from the application load balancer. This URL is visible in the Output tab of the CloudFormation stack named `Deploy-my-microservice-ecs`. Once you access the application on port 80, it will show a hello-world screen. In CodeDeploy, you can see the details of the deployment listed in the Deployments menu. The image below shows how it looks like:

<img src="/imgs/CodeDeployDeployment.png" width=80%>

At this point, refreshing the page repeatedly will show the different versions of the same application. The Green application will appear. It looks like the image below:

<img src="/imgs/blue-app.png" width=50% height=50%><img src="/imgs/green-app.png" width=50% height=50%>

