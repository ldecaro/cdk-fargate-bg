# Blue/Green in ECS Fargate with CodeDeploy using CDK

![Architecture](/imgs/architecture.png)

Requires Maven, Java8, awscli and CDK:

`# brew install maven`

```
# brew tap adoptopenjdk/openjdk
# brew install --cask adoptopenjdk8
```
`# brew install awscli`

`# brew install aws-cdk`

Build locally:
`# mvn clean package`

Deploy Blue/Green hello-world fargate microservice:
`# cdk deploy --all --require-approval never`

It creates an ECS Cluster, deploys the microservice using ECR, creates the CodeCommit repository and a minimal Pipeline. Runs the Pipeline to execute a BlueGreen deployment using deployment configuration: CodeDeployDefault.ECSLinear10PercentEvery1Minutes.

Just want the CloudFormation?

`#cdk synth`

Once the application is running, you should access it using the public URL from the application load balancer. It will show a hello-world screen like the one below:

![Architecture](/imgs/microservice.png)