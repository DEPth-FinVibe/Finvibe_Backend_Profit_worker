package depth.finvibe.profit.worker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import depth.finvibe.profit.worker.service.PriceCoalescingBuffer;

@Configuration
public class WorkerConfig {

	@Bean
	public PriceCoalescingBuffer priceCoalescingBuffer() {
		return new PriceCoalescingBuffer();
	}
}
