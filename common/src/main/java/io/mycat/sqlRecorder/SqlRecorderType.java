package io.mycat.sqlRecorder;

public enum  SqlRecorderType {
    AT_START,
    PARSE_SQL,
    COMPILE_SQL,//compile
    RBO,
    CBO,
    GET_CONNECTION,
    CONNECTION_QUERY_RESPONSE,
    EXECUTION_TIME,
    AT_END


    ///////////////////////////////////sql接收的时刻///////////////////////////////////

    ///////////////////////////////////sql解析时间//////////////////////////////////////

    ///////////////////////////////////编译sql时间//////////////////////////////////////

    ///////////////////////////////////基于规则的优化时间//////////////////////////////////////

    ///////////////////////////////////基于成本的优化时间//////////////////////////////////////

    ///////////////////////////////////获取连接时间//////////////////////////////////////

    ///////////////////////////////////获取查询到获得响应时间/////////////////////////////////////

    /////////////////////////////////////////最终响应结束时间////////////////////////////////////////////////////

}