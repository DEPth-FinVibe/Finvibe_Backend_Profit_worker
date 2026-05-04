package depth.finvibe.profit.worker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class ProfitUpdateExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService profitUpdateExecutor(
            @Value("${worker.profit.executor-type:fixed}") String executorType,
            @Value("${worker.profit.thread-count:0}") int threadCount
    ) {
        if ("virtual".equalsIgnoreCase(executorType)) {
            return Executors.newVirtualThreadPerTaskExecutor();
        }

        int poolSize = threadCount > 0 ? threadCount : Runtime.getRuntime().availableProcessors();
        return Executors.newFixedThreadPool(poolSize);
    }
}
