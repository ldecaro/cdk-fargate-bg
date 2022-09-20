#!/usr/bin/env bash
#Purpose: Bootstrap AWS CodeDeploy for BlueGreen deployment.
#Author: Luiz Decaro {lddecaro@amazon.com}
#----------------------------

red=`tput setaf 1`
green=`tput setaf 2`
reset=`tput sgr0`
orange=`tput setaf 3`
blue=`tput setaf 4`

echo 1>&2 "Bootstrapping AWS CodeDeploy for ${blue}Blue${green}Green${reset} deployment!"
#echo 1>&2 "Target account and region: $2$"
#echo 1>&2 "Using Toolchain account: $1"

DIVIDER='/'
if [ $# -ge 1 ]; then

    if [[ "$1" == *"$DIVIDER"* ]]; then

        export CDK_DEPLOY_ACCOUNT=$(echo $1 | cut -d "/" -f 1)
        export CDK_DEPLOY_REGION=$(echo $1 | cut -d "/" -f 2)
    else
        echo 1>&2 "${red}Apologies, but the second parameter seems to be wrong. It should be in the format: account/region. Example: 222222222222/us-east-1${reset}"
        echo 1>&2 "${red}Aborting..${reset}"
        exit 1
    fi    
    if [[ "$#" -eq 1 ]]; then
        npx cdk deploy CodeDeployBootstrap --parameters toolchainAccount=$CDK_DEPLOY_ACCOUNT --require-approval never
    elif [[ "$#" -eq 3 ]]; then
        npx cdk deploy CodeDeployBootstrap --parameters toolchainAccount=$3 --require-approval never
    else
        echo 1>&2 "${orange}We need one or three parameters to bootstrap AWS CodeDeploy: target_account/region --trust toolchain_account. Ex: ./codedeploy-bootstrap.sh 222222222222/us-east-1 --trust 111111111111${reset}"
    fi
else
    echo 1>&2 "${orange}We need one or three parameters to bootstrap AWS CodeDeploy: target_account/region --trust toolchain_account. Ex: ./codedeploy-bootstrap.sh 222222222222/us-east-1 --trust 111111111111${reset}"
    echo 1>&2 "${red}Aborting..${reset}"
    exit 1
fi
if [ $? -eq 0 ]; then
   echo 1>&2 "CodeDeploy bootstrapped ${green}successfully${reset} in account $CDK_DEPLOY_ACCOUNT. Script executed in region: $CDK_DEPLOY_REGION"
   unset CDK_DEPLOY_ACCOUNT
   unset CDK_DEPLY_REGION
else
   exit  $?
fi