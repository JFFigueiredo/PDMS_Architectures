package ipl.pdms.shared.configuration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.AsyncConfigurer;

/**
 * Run @Async work on caller thread for deterministic integration tests.
 */
@TestConfiguration
@Profile("integration-test")
public class IntegrationTestAsyncConfiguration implements AsyncConfigurer {

    @Override
    public AsyncTaskExecutor getAsyncExecutor() {
        return new TaskExecutorAdapter(Runnable::run);
    }
}
