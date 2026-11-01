package com.nick.jarvis;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 程序入口。
 * @SpringBootApplication 会自动扫描当前包（com.nick.jarvis）及子包下的组件，
 * 并根据引入的依赖自动完成配置。
 * @MapperScan 告诉 MyBatis 去哪个包里找 Mapper 接口。
 * @EnableScheduling 开启定时任务（检查到期的提醒）。
 */
@SpringBootApplication
@MapperScan("com.nick.jarvis.mapper")
@EnableScheduling
public class JarvisApplication {

    public static void main(String[] args) {
        SpringApplication.run(JarvisApplication.class, args);
    }
}
