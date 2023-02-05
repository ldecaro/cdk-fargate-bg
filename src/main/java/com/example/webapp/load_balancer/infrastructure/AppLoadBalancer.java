package com.example.webapp.load_balancer.infrastructure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.example.Constants;

import software.amazon.awscdk.services.ec2.IVpc;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ecs.BaseService;
import software.amazon.awscdk.services.elasticloadbalancingv2.AddApplicationActionProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationListener;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationLoadBalancer;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationProtocol;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationTargetGroup;
import software.amazon.awscdk.services.elasticloadbalancingv2.BaseApplicationListenerProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.ListenerAction;
import software.amazon.awscdk.services.elasticloadbalancingv2.TargetType;
import software.constructs.Construct;

public class AppLoadBalancer extends Construct{

    private String listenerBlueArn  = null;
    private String listenerGreenArn = null;
    private List<String> tgBlueName       = new ArrayList<>();
    private String tgGreenName      = null;    
    private SecurityGroup sg        = null;    
    ApplicationLoadBalancer alb     = null;

    ApplicationTargetGroup tgGreen = null;
    ApplicationTargetGroup tgBlue = null;

    private String envType  =   null;

    public AppLoadBalancer(Construct scope, final String id, final String envType, final IVpc vpc, final SecurityGroup sg){

        super(scope, id);

        ApplicationLoadBalancer alb = ApplicationLoadBalancer.Builder.create(this, id+"internal")
            .loadBalancerName(Constants.APP_NAME+"Alb"+envType).vpc(vpc).internetFacing(true)
            .build();

        ApplicationListener listener = alb.addListener("BgListenerBlue", BaseApplicationListenerProps.builder()
            .port(80)
            .protocol(ApplicationProtocol.HTTP)
            .build());  

        String tgGreenName = Constants.APP_NAME+"-"+envType+"-Green";
        tgGreenName = tgGreenName.length()>32 ? tgGreenName.substring(tgGreenName.length()-32) : tgGreenName;

        ApplicationTargetGroup tgGreen   =   ApplicationTargetGroup.Builder.create(this, Constants.APP_NAME+"GreenTg")
            .protocol(ApplicationProtocol.HTTP)
            .targetGroupName(tgGreenName)
            .targetType(TargetType.IP)
            .vpc(vpc)
            .build();

        ApplicationListener listenerGreen = alb.addListener("BgListenerGreen", BaseApplicationListenerProps.builder()
            .port(8080)
            .defaultTargetGroups(Arrays.asList(tgGreen))
            .protocol(ApplicationProtocol.HTTP)
            .build());

        listenerGreen.addAction(Constants.APP_NAME+"ListenerGreenAction", AddApplicationActionProps.builder()
            .action(ListenerAction.forward(Arrays.asList( tgGreen )))
            .build());

        this.listenerBlueArn = listener.getListenerArn();
        this.listenerGreenArn = listenerGreen.getListenerArn();
        this.tgGreenName= tgGreen.getTargetGroupName();
        this.sg = sg;
        this.envType = envType;
        this.alb = alb;
        this.tgGreen = tgGreen;
    }

    public void addTargetToBlueListener(final BaseService service, final Integer port, final ApplicationProtocol protocol){

        ApplicationListener listener = alb.getListeners().get(0);

        String tgBlueName = Constants.APP_NAME+"-"+envType+"-Blue-"+service.getServiceName();
        tgBlueName = tgBlueName.length()>32 ? tgBlueName.substring(tgBlueName.length()-32) : tgBlueName;
        this.tgBlueName.add(tgBlueName);

        // listener.addTargets(
        //     Constants.APP_NAME+"blue-tg", 
        //     AddApplicationTargetsProps.builder()
        //         .targetGroupName(tgBlueName)
        //         .protocol(protocol)
        //         .port(port)
        //         .targets(Arrays.asList(service))
        //         .build()); 
                
        ApplicationTargetGroup tgBlue = ApplicationTargetGroup.Builder.create(this, Constants.APP_NAME+"BlueTg")
            .protocol(ApplicationProtocol.HTTP)
            .targetGroupName(tgBlueName)
            .targetType(TargetType.IP)
            .vpc(service.getCluster().getVpc())
            .build();

        listener.addAction(Constants.APP_NAME+"ListenerBlueAction", AddApplicationActionProps.builder()
            .action(ListenerAction.forward(Arrays.asList(tgBlue)))
            .build());

        this.tgBlue = tgBlue;
    }  

    public String getLoadBalancerDnsName(){
        if(alb == null ){
            return "undefined";
        }else{
            return alb.getLoadBalancerDnsName();
        }
    }

    public String getArnBlueListener(){
        return listenerBlueArn;
    }

    public String getArnGreenListener(){
        return listenerGreenArn;
    }

    public String getNameTargetGroupBlue(){
        return tgBlueName.size() == 0 ? "" : tgBlueName.get(0);
    }

    public String getNameTargetGroupGreen(){
        return tgGreenName;
    }

    public List<ApplicationListener> getListeners(){
        return alb.getListeners();
    }

    public ApplicationTargetGroup getBlueTargetGroup(){
        return tgBlue;
    }

    public ApplicationTargetGroup getGreenTargetGroup(){
        return tgGreen;
    }    
}