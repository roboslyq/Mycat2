package io.mycat;

import java.util.Map;

/**
 * 1、Hint,翻译过来本意是"提示"的意思。此技术最初被Oracle来使用，是DBA优化的常用手段之一。
 * 2、在mycat里,是用来修改sql的,对sql进行相关优化
 * 3、Hint是Mycat平台提供给用户修改sql的一次机会，用户可以根据自己的情况对SQL进行优化修改。
 *    详情见：UserSpace#execute(int sessionId, MycatDataContext dataContext, CharBuffer charBuffer, Map<String, Object> context, Response response)
 */
public interface Hint {
    String getName();
    void accept(String buffer, Map<String, Object> t);
}