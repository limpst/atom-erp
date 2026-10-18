package com.atomeng.erp.fac.adapter.out.external;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FacExternalProperties.class)
class FacExternalConfig {

    static RestClient restClient(String baseUrl, Duration connect, Duration read) {
        var jdk = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connect).build();
        var factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(read);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Bean
    JusoClient jusoClient(FacExternalProperties p) {
        var j = p.juso();
        return new JusoClient(restClient(j.baseUrl(), j.connectTimeout(), j.readTimeout()), j);
    }

    @Bean
    BuildingRegisterClient buildingRegisterClient(FacExternalProperties p, Clock clock) {
        var r = p.buildingRegister();
        return new BuildingRegisterClient(restClient(r.baseUrl(), r.connectTimeout(), r.readTimeout()), r, clock);
    }
}
