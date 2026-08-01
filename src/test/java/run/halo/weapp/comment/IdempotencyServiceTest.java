package run.halo.weapp.comment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.weapp.MutableClock;
import run.halo.weapp.error.ApiException;
import run.halo.weapp.error.ErrorCode;

/**
 * IdempotencyService：同 key 同体返回首次结果、同 key 不同体冲突、并发单飞、TTL 过期。
 */
class IdempotencyServiceTest {

    private final MutableClock clock =
        new MutableClock(Instant.now());
    private final IdempotencyService idempotency = new IdempotencyService(clock);

    @Test
    void sameKeySameBodyReturnsFirstResultAndRunsOnce() {
        AtomicInteger executions = new AtomicInteger();
        String key = "user|route|idem-1";
        String fp = IdempotencyService.fingerprint("route", "body");
        Mono<String> action = Mono.fromCallable(() -> "result-" + executions.incrementAndGet());

        StepVerifier.create(idempotency.execute(key, fp, action))
            .expectNext("result-1").verifyComplete();
        StepVerifier.create(idempotency.execute(key, fp, action))
            .expectNext("result-1").verifyComplete();
        assertEquals(1, executions.get());
    }

    @Test
    void sameKeyDifferentBodyConflicts() {
        String key = "user|route|idem-2";
        idempotency.execute(key, IdempotencyService.fingerprint("route", "body-a"),
            Mono.just("ok")).block();
        StepVerifier.create(idempotency.execute(key,
                IdempotencyService.fingerprint("route", "body-b"), Mono.just("ok")))
            .expectErrorSatisfies(t -> {
                assertEquals(ApiException.class, t.getClass());
                assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, ((ApiException) t).code());
            })
            .verify();
    }

    @Test
    void concurrentSameKeyExecutesOnlyOnce() {
        AtomicInteger executions = new AtomicInteger();
        String key = "user|route|idem-3";
        String fp = IdempotencyService.fingerprint("route", "body");
        Mono<String> action = Mono.delay(Duration.ofMillis(50))
            .then(Mono.fromCallable(() -> "r" + executions.incrementAndGet()));

        List<String> results = Flux.range(0, 8)
            .flatMap(i -> idempotency.execute(key, fp, action))
            .collectList()
            .block();
        assertEquals(8, results.size());
        assertEquals(1, executions.get());
        results.forEach(r -> assertEquals("r1", r));
    }

    @Test
    void entryExpiresAfterTenMinutes() {
        AtomicInteger executions = new AtomicInteger();
        String key = "user|route|idem-4";
        String fp = IdempotencyService.fingerprint("route", "body");
        Mono<String> action = Mono.fromCallable(() -> "r" + executions.incrementAndGet());

        idempotency.execute(key, fp, action).block();
        clock.advance(Duration.ofMinutes(11));
        idempotency.execute(key, fp, action).block();
        assertEquals(2, executions.get());
    }

    @Test
    void failedResultIsNotCachedAndRetryReExecutes() {
        AtomicInteger executions = new AtomicInteger();
        String key = "user|route|idem-5";
        String fp = IdempotencyService.fingerprint("route", "body");
        Mono<String> action = Mono.fromCallable(() -> {
            int n = executions.incrementAndGet();
            if (n == 1) {
                throw new IllegalStateException("transient");
            }
            return "r" + n;
        });

        StepVerifier.create(idempotency.execute(key, fp, action))
            .expectError(IllegalStateException.class)
            .verify();
        // 瞬时失败后手动重试（同 key 同体）重新执行并拿到新结果
        StepVerifier.create(idempotency.execute(key, fp, action))
            .expectNext("r2").verifyComplete();
        assertEquals(2, executions.get());
    }

    @Test
    void fingerprintDiffersForDifferentBodies() {
        String a = IdempotencyService.fingerprint("route", "post-1", "昵称", "正文", "v1");
        String b = IdempotencyService.fingerprint("route", "post-1", "昵称", "正文2", "v1");
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b);
        assertEquals(64, a.length());
    }
}
