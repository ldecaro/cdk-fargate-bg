#!/usr/bin/env bash
if [[ $# -eq 2 ]]; then
    export CDK_DEPLOY_ACCOUNT=$1
    export CDK_DEPLOY_REGION=$2
    shift; shift
    cdk bootstrap $CDK_DEPLOY_ACCOUNT/$CDK_DEPLOY_REGION
    if aws iam wait role-exists --role-name AWSCodeDeployRoleForBlueGreen 2> /dev/null; 
    then 
        echo "AWS CodeDeploy role already created"; 
    else 
        npx cdk deploy aws-code-deploy-bootstrap --require-approval never
    fi
    exit $?
elif [[ $# -eq 4 ]]; then
    export CDK_DEPLOY_ACCOUNT=$1
    export CDK_DEPLOY_REGION=$2
    export CDK_DEPLOY_TRUST=$4
    shift; shift; shift;
    cdk bootstrap $CDK_DEPLOY_ACCOUNT/$CDK_DEPLOY_REGION --trust $CDK_DEPLOY_TRUST --cloudformation-execution-policies 'arn:aws:iam::aws:policy/AdministratorAccess'
    if aws iam wait role-exists --role-name AWSCodeDeployRoleForBlueGreen 2> /dev/null; 
    then 
        echo "AWS CodeDeploy role already created"; 
    else 
        npx cdk deploy aws-code-deploy-bootstrap --require-approval never
    fi
    exit $?
else
    echo 1>&2 "Provide account and region as first two args."
    echo 1>&2 "Additional args are passed through to cdk deploy."
    exit 1
fi