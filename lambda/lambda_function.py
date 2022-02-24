import subprocess
import logging
import os
import boto3

logger = logging.getLogger()
logger.setLevel(logging.INFO)

def run_command(command):
    command_list = command.split(' ')

    try:
        logger.info("Running shell command: \"{}\"".format(command))
        result = subprocess.run(command_list, stdout=subprocess.PIPE);
        logger.info("Command output:\n---\n{}\n---".format(result.stdout.decode('UTF-8')))
    except Exception as e:
        logger.error("Exception: {}".format(e))
        return False

    return True

def lambda_handler(event, context):

    if event["RequestType"] == "Create":

        appName = os.environ.get('appName')
        logger.info('AppName = {}'.format(appName))
        accountNumber = os.environ.get('accountNumber')
        logger.info('Account Number = {}'.format(accountNumber))
        roleName = os.environ.get('roleName')
        logger.info('Role Name = {}'.format(roleName))
        greenListenerArn = os.environ.get('greenListenerArn')
        logger.info('Green ListenerArn = {}'.format(greenListenerArn))
        blueListenerArn = os.environ.get('blueListenerArn')
        logger.info('Blue ListenerArn = {}'.format(blueListenerArn))
        tgNameBlue = os.environ.get('tgNameBlue')
        tgNameGreen = os.environ.get('tgNameGreen')
        logger.info('TG Name Blue  = {}'.format(tgNameBlue))
        logger.info('TG Name Green  = {}'.format(tgNameGreen))
        ecsClusterName = os.environ.get('ecsClusterName')
        ecsServiceName = os.environ.get('ecsServiceName')
        logger.info('ECS Cluster Name  = {}'.format(ecsClusterName))
        logger.info('ECS Service Name  = {}'.format(ecsServiceName))
        deploymentConfigName = os.environ.get('deploymentConfigName')
        logger.info('ECS Deployment Config Name = {}'.format(deploymentConfigName))

        client = boto3.client('codedeploy')
        try:
            logger.info('Creating CodeDeploy Application '.format(appName))
            client.create_application(
                applicationName=appName,
                computePlatform='ECS',
            )
            logger.info('Created CodeDeploy Application '.format(appName))
        except Exception as err:
            logger.error("Exception creating CodeDeploy Application: {}".format(err))
            return False

        try:
            logger.info('Creating CodeDeploy DG '.format(appName))
            client.create_deployment_group(
                applicationName=appName,
                deploymentGroupName=appName,
                deploymentConfigName=deploymentConfigName,
                serviceRoleArn=roleName,
                deploymentStyle={
                    'deploymentType': 'BLUE_GREEN',
                    'deploymentOption': 'WITH_TRAFFIC_CONTROL'
                },
                blueGreenDeploymentConfiguration={
                    'terminateBlueInstancesOnDeploymentSuccess': {
                        'action': 'TERMINATE',
                        'terminationWaitTimeInMinutes': 60
                    },
                    'deploymentReadyOption': {
                        'actionOnTimeout': 'CONTINUE_DEPLOYMENT',
                        'waitTimeInMinutes': 0
                    }
                },
                loadBalancerInfo={
                    'targetGroupPairInfoList': [
                        {
                            'targetGroups': [
                                {
                                    'name': tgNameBlue
                                },
                                {
                                    'name': tgNameGreen
                                }
                            ],
                            'prodTrafficRoute': {
                                'listenerArns': [
                                    blueListenerArn,
                                ]
                            },
                            'testTrafficRoute': {
                                'listenerArns': [
                                    greenListenerArn,
                                ]
                            }
                        },
                    ]
                },
                ecsServices=[
                    {
                        'serviceName': ecsServiceName,
                        'clusterName': ecsClusterName
                    },
                ]
            )
            logger.info('Created CodeDeploy DG '.format(appName))
        except Exception as err:
            logger.error("Exception creating CodeDeploy DG: {}".format(err))
            return False

        # WHEN YOU DELETE THE SECTION BELOW MAKE SURE TO ALSO CLEAN UP THE ROLE ASSOCIATED WITH THIS CUSTOM RESOURCE
        # client = boto3.client('codepipeline')
        # pipelineName = os.environ.get('pipelineName')
        # logger.info('Pipeline Name: = {}'.format(pipelineName))
        # client.start_pipeline_execution(
        #     name=pipelineName
        # )
    elif event["RequestType"] == "Delete":
        try:
            appName = os.environ.get('appName')
            logger.info('Deleting AppName DG = {}'.format(appName))
            client = boto3.client('codedeploy')
            response = client.delete_deployment_group(
                applicationName=appName,
                deploymentGroupName=appName
            )
            response = client.delete_application(
                applicationName=appName
            )     
        except Exception as err:
            logger.error("Exception delete CodeDeploy DG and Application: {}".format(err))
            return False
    elif event["RequestType"] == "Update":
        logger.info('Trying to udpate the CodeDeploy configurations')

    return True
    #run_command('/opt/awscli/aws deploy create-application --application-name {} --compute-platform ECS'.format(appName));
    #run_command('/opt/awscli/aws deploy create-deployment-group --application-name '+appName+' --deployment-group-name '+appName+' --deployment-config-name CodeDeployDefault.ECSLinear10PercentEvery1Minutes --service-role-arn '+roleName+' --deployment-style deploymentType=BLUE_GREEN,deploymentOption=WITH_TRAFFIC_CONTROL --load-balancer-info targetGroupPairInfoList=[{targetGroups=[{name='+tgNameBlue+'},{name='+tgNameGreen+'}],testTrafficRoute={listenerArns='+greenListenerArn+'},prodTrafficRoute={listenerArns='+blueListenerArn+'}}] --ecs-services serviceName='+appName+',clusterName='+appName+' --blue-green-deployment-configuration terminateBlueInstancesOnDeploymentSuccess={action=TERMINATE,terminationWaitTimeInMinutes=60},deploymentReadyOption={actionOnTimeout=CONTINUE_DEPLOYMENT,waitTimeInMinutes=0}')
    #run_command('/opt/awscli/aws codepipeline start-pipeline-execution --name '+pipelineName)