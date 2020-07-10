package io.mycat.booster;

import io.mycat.MycatConfig;
import io.mycat.config.PatternRootConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Booster,翻译过来是 助推器、升压器、增压器、强心针
 * 在Mycat里，主要是加速计算的节点
 */
public enum BoosterRuntime {
    INSTANCE;
    private static final Logger LOGGER = LoggerFactory.getLogger(BoosterRuntime.class);
    private MycatConfig config;
    /**
     * 缓存用户对应的DatasourceName和sql转发。
     *
     * 1、Key为username,value为targetDatasourceName
     * 通过getBooster(String name)方法返回的字符串，最终会被如下方法调用（与targetsConfig）对应：
     * String replicaName = ReplicaSelectorRuntime.INSTANCE.getDatasourceNameByReplicaName(
     *                     Objects.requireNonNull(targetsConfig, "can not get " + targetsConfig + " of " + "targets"),
     */
    private Map<String, List<String>> boosters = new HashMap<>();

    /**
     * 初始化,对应的配置如下：
     *  interceptors:
     *   [{
     *      user: {ip: '.', password: '123456', username: root},
     *      boosters: [defaultDs2],
     *      sqls:[
     *
     *      ]
     *    }]
     * @param config
     */
    public synchronized void load(MycatConfig config) {
        if (this.config != config) {
            this.config = config;
            // 获取配置文件中的interceptors标签,此标签会解析到PatternRootConfig对象中
            Stream<PatternRootConfig> configStream = Optional.ofNullable(config.getInterceptors())
                    .map(i -> i.stream()).orElse(Stream.empty());
            boosters =
                    configStream.filter(i -> i.getUser() != null)
                            .collect(Collectors
                                    // 将配置中的booster放入到当前对应的map中
                                    .toMap(k -> k.getUser().getUsername(), v -> v.getBoosters()));
        }
    }

    /**
     * 根据User获取对应的bootser缓存
     * @param name
     * @return
     */
    public Optional<String> getBooster(String name) {
        List<String> strings = boosters.getOrDefault(name, Collections.emptyList());
        if (strings.isEmpty()) {
            return Optional.empty();
        }
        int randomIndex = ThreadLocalRandom.current().nextInt(0, strings.size());
        return Optional.of(strings.get(randomIndex));
    }
}