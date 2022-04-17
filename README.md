# Blue/Green Deployments to Amazon ECS using AWS CDK and AWS CodeDeploy

A reference pipeline using AWS CodePipeline, AWS CodeBuild and AWS CodeDeploy. Works in **single** or **cross-account** scenario.

![Architecture](/imgs/stacks.png)

A single CDK command, deploys a pipeline and a git repository. The pipeline runs and deploys the Api and a Service using ECS Fargate. Api stack creates the ECS Cluster and deploys the **blue** version of the service. Using an *Inversion-of-Control (IoC)* the Deploy stage of the pipeline will use CodeDeploy to deploy the **green** service and all subsequent versions.

This strategy will allow updates to the Api and the Service stacks in a single commit. The IoC is implemented using a Self-Mutating CDK Pipeline that identifies changes to the pipeline, infrastructure or service. When this happens, it self-mutates to deploy these changes prior to executing the Blue/Green deployment. 

The Repository stack deploys a git repository that hosts a Java based microservice and CDK code that can be changed to update the Toolchain, the Api or the Service.
 <!-- I created a tarball with a single HTML container running HTTPD with a total size around 20KB but, for this reference application, I decided to create a nginx container running a single page Blue app. The size of the container of the Blue app is around 20MB and this should not impact the time the pipeline takes to execute. If this is a problem for you, you can easily create a single HTML page container with around 20KB of size and change the Infrastructure stack to load the tarball every time it executes. -->

This reference pipeline can be particularly useful in cases when:

- Changes need to be executed in the infrastructure and in the application in the same commit without external coordination of events. This is particularly useful in applications with an extense integration phase;
- Infrastructure ends up being more than just the ECS and the solution using Hooks might not be a fit for your use case;
- There is a need to use CDK with a nested stack;
- There is a requirement to use one the features not available using CloudFormation Hooks  listed under [considerations](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/blue-green.html#blue-green-considerations).

***Why CodeDeploy?***

Deploying containers at an enterprise level might require these capabilities:

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
cdk deploy ecs-microservice-toolchain --require-approval never
```

- Cross-Acccount
```
cdk bootstrap aws://987654321098/us-east-1
cdk bootstrap aws://123456789012/us-east-1

#cross-account with onle Beta stage in the remote account
cdk deploy ecs-microservice-toolchain -c beta=12345678910/us-east-1 --require-approval never

#cross-account  with alpha and beta in remote accounts
cdk deploy ecs-microservice-toolchain -c alpha=12346787901/us-east-1 -c beta=987654321098/us-east-1 --require-approval never
```

<!-- It creates an ECS Cluster, deploys the service using ECR, creates the CodeCommit repository and a reference Pipeline. Runs the Pipeline to execute a BlueGreen deployment using deployment configuration: *CodeDeployDefault.ECSLinear10PercentEvery1Minutes*.

When the pipeline is deployed it will build the project and, in the Deploy, stage it will configure CodeDeploy and execute two stacks in parallel: ECSStack and ServiceAssetStack. ECSStack deploys the ECS Fargate Infrastructure including the Blue Application and ServiceAssetStack deploys the Green application in ECR using CDK's DockerImageAsset. Once the ECSStack is deployed and Green application is uploaded into ECR, CodeDeploy is invoked and Blue/Green Deployment takes place. -->

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

