package depth.finvibe.profit.worker.infrastructure.redis;

/**
 * 파이프라인 실행 결과 개수가 예상과 다를 때 발생.
 * 인덱스 기반 파싱에서 잘못된 데이터를 조용히 읽는 것을 방지한다.
 */
public class PipelineResultMismatchException extends RuntimeException {

	public PipelineResultMismatchException(String operation, int expected, int actual) {
		super("Pipeline result count mismatch in " + operation
				+ ": expected=" + expected + ", actual=" + actual);
	}
}
