#!/usr/bin/env bash
#Purpose: Bootstrap AWSCodeDeploy for BlueGreen deployment in target account.
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
if [[ "$#" -eq 2 ]]; then

    if [[ "$2" == *"$DIVIDER"* ]]; then

        export CDK_DEPLOY_ACCOUNT=$(echo $2 | cut -d "/" -f 1)
        export CDK_DEPLOY_REGION=$(echo $2 | cut -d "/" -f 2)
    else
        echo 1>&2 "${red}Apologies, but the second parameter seems to be wrong. It should be in the format: account/region. Example: 222222222222/us-east-1${reset}"
        echo 1>&2 "${red}Aborting..${reset}"
        exit 1
    fi
    npx cdk deploy BootstrapCodeDeploy --parameters toolchainAccount=$1 --require-approval never
else
    echo 1>&2 "${orange}We need two parameters to bootstrap AWSCodeDeploy: toolchain_account_number and target_account/region. Ex: ./codedeploy-bootstrap.sh 111111111111 222222222222/us-east-1${reset}"
    echo 1>&2 "${red}Aborting..${reset}"
    exit 1
fi

if [ $? -eq 0 ]; then
   echo 1>&2 "CodeDeploy bootstrapped ${green}successfully${reset} in account $(echo $2 | cut -d "/" -f 1). Script executed in region: $(echo $2 | cut -d "/" -f 2)"
else
   exit  $?
fi