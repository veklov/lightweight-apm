package io.opentelemetry.example.client;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
public class Application implements CommandLineRunner {
    private static final Logger LOGGER = LogManager.getLogger(Application.class);

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Override
    public void run(String... args) {
        RestTemplate restTemplate = new RestTemplate();

        while(true) {
            try {
                String ping = restTemplate.getForObject("http://app:8080/ping", String.class);
                LOGGER.info("ping -> {}", ping);
            } catch (RestClientException e) {
                LOGGER.error("ping -> {}", e.toString());
            }

            try {
                String fail = restTemplate.getForObject("http://app:8080/fail", String.class);
                LOGGER.info("fail -> {}", fail);
            } catch (RestClientException e) {
                LOGGER.info("fail -> {}", e.toString());
            }

            try {
                Thread.sleep(1000L);
            } catch (InterruptedException ignored) {
            }
        }
    }
}
