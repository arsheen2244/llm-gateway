package dev.llmgateway.mock.behavior;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MockConfig {

    /**
     * The random source behind the error and failure rates. It is a bean so unit
     * tests can pass a fixed sequence instead. ThreadLocalRandom has no
     * contention between request threads.
     */
    @Bean
    DoubleSupplier mockRandom() {
        return () -> ThreadLocalRandom.current().nextDouble();
    }
}
