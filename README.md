# load4s
load testing for scala

## k8s部署
打包到本地
默认镜像名为：dounine/load4s:0.1.0-SNAPSHOT
```
sbt clean docker:publishLocal
```
可以修改build.sbt中的dockerUsername为自己仓库的名字并打包上传
```shell
sbt clean docker:publish
```
创建命名空间
```shell
kubectl apply -f namespace.json
```
创建权限
```shell
kubectl apply -f role.yaml
```
修改pro.yaml里面的地扯为redis的
```shell
kubectl apply -f pro.yaml
```
访问阿里云暴露的IP+端口即可访问

## UDP服务端
```shell
sbt clean dist
```
解压运行
```
cd target/universal
target/universal
cd load4s-0.1.0-SNAPSHOT/bin
export REDIS_HOST=localhost && export REDIS_PORT=6379 export REDIS_PASSWORD=123456 &&  ./udp-server
```
export 参数列表
```
redis地扯：REDIS_HOST        默认：dev2
redis端口：REDIS_PORT        默认：6379
redis密码：REDIS_PASSWORD    默认：空

暴露UDP服务端口：CLIENT_PORT      默认：8080
每条数据CPU执行次数：CLIENT_CPU    默认：3     (模拟CPU高压)
默认每几秒保存数据到Redis中：CLIENT_DURATION   默认：3秒
```