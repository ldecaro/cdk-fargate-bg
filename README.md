# Blue/Green in ECS Fargate with CodeDeploy using CDK

This is a reference application for running a microservice in ECS Fargate that uses CodePipeline, CodeBuild, CodeDeploy and implements a Blue/Green approach for deploying applications in a **single** or **cross-account** scenario.

![Architecture](/imgs/stacks.png)

This reference application creates, for each microservice, the infrastructure defined by a set of 4 stacks: Git (GitStack), Pipeline (PipelineStack), ECS (ECSStack) and Microservice (AssetStack). It implements an *Inversion of Control (IoC)*, taking away the ownership to deploy apps from the ECS stack and making CodeDeploy responsible for deploying the Green ECS Fargate Task. As a result, it allows updates to the ECS Stack and to the microservice triggering the Blue/Green deployment, all in the same commit.

In addition to that, constraints from using CloudFormation Hooks are not present. With the multi-stack approach, some capabilities not available in the solution using [Hooks](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/blue-green.html), like the ability to *Stop a Deployment* or *Stop and Rollback Deployment*, can be accessed directly from AWS CodeDeploy.

The Inversion-of-Control (IoC) is implemented with the use of CodeDeploy to deploy the Green application and subsequent versions of it. This is enabled with the use of a Self-Mutating CDK Pipeline that identifies changes into the ECSStack and self-mutates to deploy these changes prior to executing the blue/Green deployment. 

When the PipelineStack is executed, it also executes the GitStack and, as result, it is triggered. The PipelineStack runs and builds the application, self-mutates and executes the ECSStack. When the ECSStack is created, it deploys the Blue Application. We must make sure the blue application is never changed from this point forward. This is a requirement, because we want to implement an IoC and have CodeDeploy making changes to the application. Initially, I created a tarball with a single HTML container running HTTPD with a size around 20KB but, for this reference application, decided to create a nginx container running the single page blue app. The size of the container of the blue app is around 20MB and this should not impact the time the pipeline takes to execute. If this is a problem for you, you can easily create a single HTML page container with around 20KB and change ECSStack to load the tarball every time it executes.

This reference application can be particularly useful in cases when:

- Application has too many tests, including funcional tests and you need the pipeline to run through all these tests just to make a change into the underlying infrastructure;
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
Requires Docker, Maven, Java8, awscli and CDK


```
# brew install docker
# brew install maven
# brew tap adoptopenjdk/openjdk
# brew install --cask adoptopenjdk8
# brew install awscli
# brew install aws-cdk
```
## Build 
Download the github project and build project locally:

```
# git clone https://github.com/ldecaro/cdk-fargate-bg.git
# mvn clean package
```
## Deploy

Deploy Blue/Green fargate microservice:

```
cdk deploy my-microservice-pipeline --require-approval never
```

It creates an ECS Cluster, deploys the microservice using ECR, creates the CodeCommit repository and a minimal Pipeline. Runs the Pipeline to execute a BlueGreen deployment using deployment configuration: *CodeDeployDefault.ECSLinear10PercentEvery1Minutes*.

When the pipeline is deployed it will build the project and, in the Deploy, stage it will configure CodeDeploy and execute two stacks in parallel: ECSStack and ServiceAssetStack. ECSStack deploys the ECS Fargate Infrastructure including the Blue Application and ServiceAssetStack deploys the Green application in ECR using CDK's DockerImageAsset. Once the ECSStack is deployed and Green application is uploaded into ECR, CodeDeploy is invoked and Blue/Green Deployment takes place.

<img src="/imgs/pipeline-1.png" width=100% >
<img src="/imgs/pipeline-2.png" width=100% >

## Testing 

Then the pipeline reaches the latest action of the Deploy stage the application will become acessible for the first time. You can test the application by using the public URL from the application load balancer. This URL is visible in the Output tab of the CloudFormation stack named `Deploy-my-microservice-ecs`. Once you access the application on port 80, it will show a hello-world screen. In CodeDeploy, you can see the details of the deployment listed in the Deployments menu. The image below shows how it looks like:

<img src="/imgs/CodeDeployDeployment.png" width=80%>

At this point, refreshing the page repeatedly will show the different versions of the same application. The Green application will appear. It looks like the image below:

<img src="/imgs/blue-app.png" width=50% height=50%><img src="/imgs/green-app.png" width=50% height=50%>

