package depth.finvibe.profit.worker.infra.redis;

import depth.finvibe.profit.worker.infra.redis.model.Portfolio;
import org.springframework.data.repository.CrudRepository;

public interface PortfolioRedisRepository extends CrudRepository<Portfolio, Long> {
}
