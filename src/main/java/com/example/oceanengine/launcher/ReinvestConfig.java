package com.example.oceanengine.launcher;

import com.example.oceanengine.scheduler.SuixintuiReinvestProperties;
import com.example.oceanengine.storage.ReinvestRecordStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * 追投服务 Bean 装配（把有构造依赖的存储组件独立配置，避免与启动类形成循环依赖）。
 */
@Configuration
public class ReinvestConfig {

    /** 幂等流水存储（JSONL 文件），启动时自动加载历史记录 */
    @Bean
    public ReinvestRecordStore reinvestRecordStore(SuixintuiReinvestProperties props) throws IOException {
        return new ReinvestRecordStore(props.getRecordStorePath());
    }
}
