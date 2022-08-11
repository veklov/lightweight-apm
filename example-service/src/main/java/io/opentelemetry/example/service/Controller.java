package io.opentelemetry.example.service;

import io.opentelemetry.extension.annotations.WithSpan;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.Random;

@RestController
public class Controller {

    private static final Logger LOGGER = LogManager.getLogger(Controller.class);

    private final Random random = new Random();
    private RestTemplate restTemplate;

    @Autowired
    Controller(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @GetMapping("/ping")
    public String ping() throws InterruptedException {
        doWork();
        return "pong";
    }

    @GetMapping("/fail")
    public String fail() throws InterruptedException {
        if (random.nextInt(100) < 25) throw new RuntimeException("ok");
        rest();
        return "nope";
    }

    @WithSpan
    private void doWork() throws InterruptedException {
        String html = restTemplate.getForObject("http://google.com", String.class);
        LOGGER.info("Response from google {}", html.substring(0, 30));
        rest();
        html = restTemplate.getForObject("http://gmail.com", String.class);
        LOGGER.info("Response from gmail {}", html.substring(0, 30));
    }

    @WithSpan
    private void rest() throws InterruptedException {
        Thread.sleep(random.nextInt(200));
    }
}
