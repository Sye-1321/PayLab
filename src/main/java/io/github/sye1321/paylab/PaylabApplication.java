package io.github.sye1321.paylab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PaylabApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaylabApplication.class, args);
    }
}
