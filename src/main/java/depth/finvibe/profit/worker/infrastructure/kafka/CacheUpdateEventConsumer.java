package depth.finvibe.profit.worker.infrastructure.kafka;

import depth.finvibe.profit.worker.application.port.in.CacheUpdateUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CacheUpdateEventConsumer {

    private final CacheUpdateUseCase cacheUpdateUseCase;

    // TODO: Kafka listener를 추가하고 매수/매도, 포트폴리오 생성/삭제 이벤트를 CacheUpdateUseCase DTO로 매핑한다.
}
