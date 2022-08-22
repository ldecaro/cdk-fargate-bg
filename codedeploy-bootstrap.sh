#!/usr/bin/env bash
echo 1>&2 "Bootstrapping AWS CodeDeploy. Use environment variables CDK_DEPLOY_ACCOUNT, CDK_DEPLOY_REGION and CDK_DEPLOY_PIPELINE to configure"
echo 1>&2 "Using CDK_DEPLOY_ACCOUNT: $CDK_DEPLOY_ACCOUNT"
echo 1>&2 "Using CDK_DEPLOY_ACCOUNT: $CDK_DEPLOY_REGION"
echo 1>&2 "Using CDK_DEPLOY_ACCOUNT: $CDK_DEPLOY_PIPELINE"
#if [[ $# -eq 2 ]]; then
    #export CDK_DEPLOY_ACCOUNT=$1
    #export CDK_DEPLOY_REGION=$2
    #export CDK_DEPLOY_PIPELINE=$2
#    shift; shift 
#fi
npx cdk deploy BootstrapCodeDeploy --require-approval never
exit $?
