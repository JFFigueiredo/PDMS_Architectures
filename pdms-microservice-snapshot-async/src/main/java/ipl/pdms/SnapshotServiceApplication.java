package ipl.pdms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "ipl.pdms")
@EnableJpaRepositories(basePackages = "ipl.pdms")
@EnableAsync
@EnableScheduling
public class SnapshotServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SnapshotServiceApplication.class, args);
    }

}
