# DSC
Distributed SDN Controller  
分布式SDN控制器

###项目简介

<p>本应用提供了一套基于分布式系统的 SDN 控制器集群解决方案，在保证单点控
制器稳定工作的同时，实现了多自治域的控制器集群。应用中控制器通过发送
TCP/IP 心跳包的机制来完成控制器之间的相互发现。通过对 LLDP 协议中 TLV 标记
的修改来完成全局链路发现，并在此基础上完成了链路层的故障检测恢复与具有集
群功能的 MAC 自学习模块。 与此同时， 借助发布/订阅模型和分布式数据库实现了全
局流表下发和控制器角色主从切换请求下发以及全局信息查询，并在此基础上实现
了平衡控制器负载的负载均衡功能，从而达到了分布式控制器集群的要求。

###涉及技术

Hazelcast(分布式内存网格数据库，负责提供集群数据共享以及集群发现等功能)

floodlight(著名开源SDN控制器)

###项目文档
这里提供我们的复赛报告下载地址，其中包含了系统的设计思想已经流程图，如果有兴趣可以一起探讨。
下载地址：http://download.csdn.net/detail/mr253727942/9456253
