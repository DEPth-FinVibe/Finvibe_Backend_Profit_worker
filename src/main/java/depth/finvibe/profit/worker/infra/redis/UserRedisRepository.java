package depth.finvibe.profit.worker.infra.redis;

import depth.finvibe.profit.worker.infra.redis.model.User;
import org.springframework.data.repository.CrudRepository;

public interface UserRedisRepository extends CrudRepository<User, Long> {
}
