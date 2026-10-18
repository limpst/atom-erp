package com.atomeng.erp.sal.adapter.out.g2b;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(G2bProperties.class)
class G2bConfig {

    static RestClient restClient(G2bProperties p) {
        var jdk = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(p.connectTimeout()).build();
        var factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(p.readTimeout());
        return RestClient.builder().baseUrl(p.baseUrl()).requestFactory(factory).build();
    }

    @Bean
    G2bApiClient g2bApiClient(G2bProperties p, ObjectMapper om) {
        return new G2bApiClient(restClient(p), p, om);
    }
}
