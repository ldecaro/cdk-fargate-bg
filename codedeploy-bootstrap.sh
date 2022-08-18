#!/usr/bin/env bash
echo 1>&2 "Bootstrapping AWS CodeDeploy. If deploying in cross-account, please add --trust {account_number}"
if [[ $# -eq 2 ]]; then
    #export CDK_DEPLOY_ACCOUNT=$1
    #export CDK_DEPLOY_REGION=$2
    export CDK_DEPLOY_PIPELINE=$2
    shift; shift 
fi
npx cdk deploy BootstrapCodeDeploy --require-approval never
exit $?
