package cn.aimstek.loong.aidiag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));   // 连接超时 10 秒
        factory.setReadTimeout(Duration.ofSeconds(120));     // 读取超时 120 秒（LLM 需要较长时间）
        return new RestTemplate(factory);
    }
}
