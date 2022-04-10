### 简介

 etcd这个名字源于两个想法，即unix “/etc” 文件夹和分布式系统”d”istibuted。 “/etc” 文件夹为单个系统存储配置数据的地方，而 etcd 存储大规模分布式系统的配置信息。因此，”d”istibuted　的 “/etc” ，故而称之为 “etcd”。

 etcd 是一个用Go编写的高可用 Key/Value 存储系统，分布式系统中最关键的数据的分布式、可靠的键值存储（配置文件基本格式为key=value ），etcd 的灵感来自于 ZooKeeper 和 Doozer，它具有出色的跨平台支持，小型二进制文件和背后的优秀社区。



### 集群部署

https://blog.csdn.net/wjh5240313226/article/details/114490431



### 证书配置

https://blog.csdn.net/yangshihuz/article/details/111873186

**注意：** 

执行sed -i 's/http/https/g' /etc/etcd/etcd.conf 后，需要将/etc/etcd/etcd.conf文件中的ETCD_LISTEN_CLIENT_URLS参数的https://127.0.0.1:2379改成http://127.0.0.1:2379



### JETCD使用证书

```
snoopy:
  grpc:
    registry:
      protocol: etcd
      address: 192.168.162.128:2379,192.168.162.129:2379,192.168.162.130:2379
      extra:
        usePlaintext: false
        authority: etcd
        caCertFile: classpath:etcd-cert/ca.pem
        certFile: classpath:etcd-cert/server.pem
        keyFile: classpath:etcd-cert/server-key.pkcs8.pem
```