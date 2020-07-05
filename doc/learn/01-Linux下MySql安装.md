# Linux下MySql安装

## 下载Mysql

>  wget https://dev.mysql.com/get/mysql57-community-release-el7-11.noarch.rpm

## 安装Mysql

> yum localinstall -y mysql57-community-release-el7-11.noarch.rpm

##　检查安装结果

> systemctl status mysqld

## 启动Mysql

>  systemctl start mysqld

## 修改root密码

>查找默认密码：
>
>> grep 'temporary password' /var/log/mysqld.log
>
>修改密码：
>
>> use mysql
>>
>> ALTER USER USER() IDENTIFIED BY 'l!vWT#mL93';



## 允许用户远程登录

如果不执行下面语会报如下异常：is not allowed to connect to this MySQL server

> GRANT ALL PRIVILEGES ON *.* TO 'root'@'%' IDENTIFIED BY 'l!vWT#mL93' WITH GRANT OPTION;

## 关防火墙

>systemctl stop firewalld.service
>
>systemctl disable firewalld.service

create user test_user identified by '1q2w3e4r(A'; 

grant all on test_user.* to 'test_user'@'%';

flush privileges;

