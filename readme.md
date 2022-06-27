# ots-table-backup 帮助文档

<p align="center" class="flex justify-center">
    <a href="https://www.serverless-devs.com" class="ml-1">
    <img src="http://editor.devsapp.cn/icon?package=ots-table-backup&type=packageType">
  </a>
  <a href="http://www.devsapp.cn/details.html?name=ots-table-backup" class="ml-1">
    <img src="http://editor.devsapp.cn/icon?package=ots-table-backup&type=packageVersion">
  </a>
  <a href="http://www.devsapp.cn/details.html?name=ots-table-backup" class="ml-1">
    <img src="http://editor.devsapp.cn/icon?package=ots-table-backup&type=packageDownload">
  </a>
</p>

<description>

通过[函数计算](https://www.aliyun.com/product/fc?spm=5176.19720258.J_3207526240.111.28b52c4aqlAUqO)来实现定时备份[表格存储](https://www.aliyun.com/product/ots?spm=5176.137990.J_3207526240.34.2e9b1608VLksvP)中的表任务

</description>

<table>

## 前期准备
| 服务/业务 | 函数计算           |
| --------- | ------------------ |
| 权限/策略 | AliyunFCFullAccess |

</table>

<codepre id="codepre">

</codepre>

<deploy>

## 部署 & 体验

<appcenter>

- :fire: 通过 [Serverless 应用中心](https://fcnext.console.aliyun.com/applications/create?template=ots-table-backup) ，
[![Deploy with Severless Devs](https://img.alicdn.com/imgextra/i1/O1CN01w5RFbX1v45s8TIXPz_!!6000000006118-55-tps-95-28.svg)](https://fcnext.console.aliyun.com/applications/create?template=ots-table-backup)  该应用。 

</appcenter>

- 通过 [Serverless Devs Cli](https://www.serverless-devs.com/serverless-devs/install) 进行部署：
    - [安装 Serverless Devs Cli 开发者工具](https://www.serverless-devs.com/serverless-devs/install) ，并进行[授权信息配置](https://www.serverless-devs.com/fc/config) ；
    - 初始化项目：`s init ots-table-backup -d ots-table-backup`   
    - 进入项目，并进行项目部署：`cd ots-table-backup && s deploy -y`

</deploy>

<appdetail id="flushContent">

# 应用详情

使用表格存储后，比较常见的运维场景是主备表间进行同步。通过该项目，可以利用函数计算完成将表格存储的源表定时备份到目标表的功能，实现按量付费，免去购买及运维服务器的烦恼。

>通过本应用，还可以完整地体验从 SpringBoot 单体应用平滑迁移函数计算的流程

## 初始化参数
| 参数名称       | 参数类型 | 是否必填 | 例子                                                      | 参数含义                                                                                               |
| -------------- | -------- | -------- | --------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| serviceName    | String   | 选填     | ots-table-backup                                          | 函数服务名称名                                                                                         |
| functionName   | String   | 选填     | ots-table-backup                                          | 函数名称                                                                                               |
| roleArn        | String   | 必填     | acs:ram:\:\<accountId>:role/aliyuncdnserverlessdevsrole        | 函数执行角色                                                                                           |
| sourceEndpoint | String   | 必填     | https://\<instanceId>.<region>.ots-internal.aliyuncs.com | 源表所在实例endpoint                                                                                   |
| targetEndpoint | String   | 必填     | https://\<instanceId>.\<region>.ots-internal.aliyuncs.com | 目标表所在实例endpoint                                                                                 |
| sourceTable    | String   | 必填     | source-table                                              | 源表名名                                                                                               |
| targetTable    | String   | 必填     | target-table                                              | 目标表名名                                                                                             |
| tunnelType     | String   | 选填     | BaseData                                                  | [通道类型](https://help.aliyun.com/document_detail/102489.html)                                        |
| backupEndTime  | String   | 选填     | '2022-07-01 00:00:00'                                     | 使用增量备份时，备份的截止时间，yyyy-MM-dd HH:mm:ss                                                    |
| dropIfExist    | Boolean  | 选填     | False                                                     | 目标表存在时是否先删除目标表，保证目标表和源表的完全一致                                               |
| cronExpression | String   | 选填     | '@every 60m'                                              | 定时触发时间，参考 [函数计算](https://help.aliyun.com/document_detail/171746.html#section-gbz-k3r-vum) |

## 工作原理
* 利用表格存储的[通道服务](https://help.aliyun.com/document_detail/102489.html)，将其改造成Serverless形态，完成从源表到目标的复制功能
* 使用函数计算的 [Custom Runtime](https://help.aliyun.com/document_detail/191342.html) 将单体SpringBoot项目进行函数化，完全贴合传统开发体验，实现零代码改造
* 使用函数计算的 [生命周期回调](https://help.aliyun.com/document_detail/425056.html)，将表初始化的逻辑封装到 Initializer 中，将通道消费的逻辑封装到 Invoke 中，将连接池释放的逻辑封装到 PreStop 中

![alt](https://img.alicdn.com/imgextra/i4/O1CN0156mNCE1Ii9WJk11BD_!!6000000000926-2-tps-981-1071.png)


部署完成后，会创建两个函数:
1. ots-table-mock：用于测试，当指定的源表不存在时，会使用mock数据自动构造一张
2. ots-table-backup：用于表的备份


## 执行效果
1. 执行 ots-table-mock 函数，自动构造一张源表
   ![alt](https://img.alicdn.com/imgextra/i4/O1CN01FKrVEI1XcrB9TAgO3_!!6000000002945-2-tps-3742-1146.png)
2. 查看源表已经完成创建
   ![alt](https://img.alicdn.com/imgextra/i1/O1CN01PIsaEW1Kv5wdCc5eH_!!6000000001225-0-tps-3366-1462.jpg)
3. 执行 ots-table-backup 函数，进行表备份
   ![alt](https://img.alicdn.com/imgextra/i1/O1CN017PxSZt1DN1AJ25i0Y_!!6000000000203-2-tps-2514-1436.png)
   ![alt](https://img.alicdn.com/imgextra/i2/O1CN01AtsRg11Yd92dYAs9C_!!6000000003081-0-tps-2548-1308.jpg)
   ![alt](https://img.alicdn.com/imgextra/i3/O1CN01HAbyKW1c1MpsQVk2X_!!6000000003540-2-tps-2490-980.png)
4. 查看备份结果
   ![alt](https://img.alicdn.com/imgextra/i1/O1CN01gonw031w0iacmHKN6_!!6000000006246-0-tps-3406-1546.jpg)
5. 开启定时备份
   ![alt](https://img.alicdn.com/imgextra/i1/O1CN0126uyAJ1x9hyuchxc9_!!6000000006401-0-tps-3794-1000.jpg)

</appdetail>

<devgroup>

## 开发者社区

您如果有关于错误的反馈或者未来的期待，您可以在 [Serverless Devs repo Issues](https://github.com/serverless-devs/serverless-devs/issues) 中进行反馈和交流。如果您想要加入我们的讨论组或者了解 FC 组件的最新动态，您可以通过以下渠道进行：

<p align="center">

| <img src="https://serverless-article-picture.oss-cn-hangzhou.aliyuncs.com/1635407298906_20211028074819117230.png" width="130px" > | <img src="https://serverless-article-picture.oss-cn-hangzhou.aliyuncs.com/1635407044136_20211028074404326599.png" width="130px" > | <img src="https://serverless-article-picture.oss-cn-hangzhou.aliyuncs.com/1635407252200_20211028074732517533.png" width="130px" > |
| --------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| <center>微信公众号：`serverless`</center>                                                                                         | <center>微信小助手：`xiaojiangwh`</center>                                                                                        | <center>钉钉交流群：`33947367`</center>                                                                                           |

</p>

</devgroup>