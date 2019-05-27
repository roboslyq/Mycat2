# Mycat 2

Proxy-centric high-performance MySQL Middleware.

## configuration 

the kinds of configuration that don't changed frequently.

### mycat

#### ip

the ip of mycat server 

#### port

the port of mycat server 

### replicas

a replica treated as a consistent mysql internal load balancing.

#### replica -name

the the name of replica that can be reference by data node config in schema config

#### repType

type of replica:x:

#### balanceName

reference load balance name that be in plug config

#### mysqls

configure multiple mysql connection config

#### datasource-mysql

##### name

the the name of mysql connection info

##### ip

the ip of mysql server 

##### port

the port of mysql server 

##### user

username of mysql user

##### password

password of mysql user

##### minCon

the number of init mysql connection

##### maxCon

the number of limit mysql connection



### masterIndexes

datasource switch record index

```
masterIndexes:
  repli: 0
  repli2: 0
```

repli is a replica  name that in datasource config

the number 0 is mysqls index  in datasource confg marks as matser mysql server

when the master node switches, the number changes to new master index



### schema

schema

a logic schema 

table,dataNode



##### 







## verification

### packet splitting

for example,client or server  send 2^24 -1 bytes payload int two packets. 

###  session status

checking pass status correctly from MySQL client to backend MySQL server.

```sql
SET autocommit = {1|0};
SET names {charset};
SET character_set_results  = {charset};
SET SESSION TRANSACTION ISOLATION LEVEL {isolation};
USE {schema};
```

### hold the session

When front client has the following status,proxy should hold the backend client until end of the interaction process.

- transaction
- prepare statement(according to the specific implementation):x:
- loaddata infile:x:

## package

```
cd mycat2
compile assembly:single
```

or you can

set the Working directory to mycat2  and

set command line to compile assembly:single

in your run/debug configuration.



## run/debug

path to the configuration file(resources) as MYCAT_HOME added to VM options.

```
java -Dfile.encoding=UTF-8 -DMYCAT_HOME=D:\xxxxxxx -jar mycat2-0.1.jar 
```

## communicate

leave a message on issues.

Maintain together to make the project more robust.

QQ:294712221

## License

GPLv2