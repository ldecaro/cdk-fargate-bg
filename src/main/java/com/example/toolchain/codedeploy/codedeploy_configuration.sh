##
#!/bin/sh
#
# This file contains a set of commands that will be executed inside the action Configure AWS CodeDeploy
# It replaces tokens inside the files template-appspec.yaml and template-taskdef.json to customize it
# to the environment based in the following information:
#
# Account = The Account Id
# AppName = Name of the application
# StageName = Name of the stage
# Region = Name of the region (us-east-1, us-east-2)
# PipelineId = Id of the pipeline
#
# Primary output directory is codedeploy. All the 3 files created (appspec.json, imageDetail.json and 
# taskDef.json) will be located inside the codedeploy directory
#
##
Account=$1
Region=$2
AppName=$3
StageName=$4
PipelineId=$5
ls -l
ls -l codedeploy
repo_name=$(cat assembly*$PipelineId-$StageName/*.assets.json | jq -r '.dockerImages[] | .destinations[] | .repositoryName' | head -1) 
tag_name=$(cat assembly*$PipelineId-$StageName/*.assets.json | jq -r '.dockerImages | to_entries[0].key')  
echo ${repo_name} 
echo ${tag_name} 
printf '{"ImageURI":"%s"}' "$Account.dkr.ecr.$Region.amazonaws.com/${repo_name}:${tag_name}" > codedeploy/imageDetail.json                     
sed 's#APPLICATION#'$AppName'#g' codedeploy/template-appspec.yaml > codedeploy/appspec.yaml 
sed 's#APPLICATION#'$AppName'#g' codedeploy/template-taskdef.json | sed 's#TASK_EXEC_ROLE#arn:aws:iam::'$Account':role/'$AppName'-'$StageName'#g' | sed 's#fargate-task-definition#'$AppName'-'$StageName'#g' > codedeploy/taskdef.json 
cat codedeploy/appspec.yaml
cat codedeploy/taskdef.json
cat codedeploy/imageDetail.json