package com.example;

public interface Constants {

    public static final String APP_NAME = "ExampleMicroservice";

    // public interface Stage{

    //     public static final String[][] DEPLOY_STAGES = new String [][] { {"UAT", Toolchain.COMPONENT_ACCOUNT, Toolchain.COMPONENT_REGION}  };
    // }

    public interface CodeDeploy{

        public static final String ROLE_NAME_DEPLOY = "AWSCodeDeployRoleForBlueGreen";
    }
}