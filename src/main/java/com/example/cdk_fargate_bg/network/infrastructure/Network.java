package com.example.cdk_fargate_bg.network.infrastructure;

import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.Vpc;
import software.constructs.Construct;

public class Network extends Construct {

    Vpc vpc = null;
    public Network (Construct scope, final String id, String appName){

        super(scope,id);

        Vpc vpc = Vpc.Builder.create(this, appName+"VPC") 
            .maxAzs(2)
            .natGateways(1)
            .enableDnsHostnames(Boolean.TRUE)
            .enableDnsSupport(Boolean.TRUE)            
            .build();
    
        SecurityGroup sg    =   SecurityGroup.Builder.create(this, appName+"Sg")
            .vpc(vpc)
            .allowAllOutbound(Boolean.TRUE)
            .build();

        sg.addIngressRule(Peer.anyIpv4(), Port.allTcp());
        sg.addIngressRule(Peer.anyIpv4(), Port.allUdp());

        this.vpc    =   vpc;
    }
    
    public Vpc getVpc(){
        return this.vpc;
    }
}
