# Blue/Green in ECS Fargate with CodeDeploy using CDK

![Architecture](/imgs/architecture.png)

Requires Docker, Maven, Java8, awscli and CDK:

`# brew install maven`

```
# brew tap adoptopenjdk/openjdk
# brew install --cask adoptopenjdk8
```
`# brew install awscli`

`# brew install aws-cdk`

Build locally:
`# mvn clean package`

Before deploying with CDK, make sure Docker is running on your machine. CDK builds the container before pushing it to ECR.

Deploy Blue/Green fargate microservice:
`cdk deploy -c appName=my-microservice my-microservice-pipeline --require-approval never`

It creates an ECS Cluster, deploys the microservice using ECR, creates the CodeCommit repository and a minimal Pipeline. Runs the Pipeline to execute a BlueGreen deployment using deployment configuration: CodeDeployDefault.ECSLinear10PercentEvery1Minutes.

The microservice configures itself making use of a feature called self-mutating pipelines from CDK Pipelines. After configuring itself it will stop. You should update the file `src/main/java/com/example/home.html` inside the CodeCommit repository and change the property `color` at the line 13. You should change the color from `blue` to `green` like in the image below:
![BlueGreen](/imgs/blue-green.png)

Just want the CloudFormation?

`#cdk synth`

Once the application is running, you should access it using the public URL from the application load balancer. You can verify the URL in the Output tab of the CloudFormation stack named `Deploy-my-microservice-ecs`. Once you access the application on port 80, it will show a hello-world screen like the one below:

![Architecture](/imgs/microservice.png)