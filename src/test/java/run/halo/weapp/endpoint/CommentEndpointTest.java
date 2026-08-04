package run.halo.weapp.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import run.halo.weapp.comment.CommentService;
import run.halo.weapp.error.ApiException;
import run.halo.weapp.error.ErrorCode;

class CommentEndpointTest {

    private CommentService commentService;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        commentService = mock(CommentService.class);
        client = WebTestClient.bindToRouterFunction(new CommentEndpoint(commentService).endpoint())
            .build();
    }

    @Test
    void momentRouteDerivesSubjectFromPathAndNeverFromBody() {
        when(commentService.submitMomentComment(eq("token"), eq("idempotency"), eq("0.4.0"),
            any(), any())).thenReturn(Mono.just(new CommentService.WriteResult("c-1", true)));

        client.post().uri("/moments/moment-1/comments")
            .header("X-WeApp-Session", "token")
            .header("X-Idempotency-Key", "idempotency")
            .header("X-WeApp-Client-Version", "0.4.0")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"displayName":"访客昵称","content":"正文","privacyConsentVersion":"v1",
                 "subjectRef":{"group":"evil","kind":"Post","version":"v1","name":"post-1"}}
                """)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("published")
            .jsonPath("$.commentName").isEqualTo("c-1")
            .jsonPath("$.requestId").isNotEmpty()
            .jsonPath("$.retryAfter").doesNotExist();

        var command = org.mockito.ArgumentCaptor.forClass(CommentService.MomentCommentCommand.class);
        verify(commentService).submitMomentComment(eq("token"), eq("idempotency"), eq("0.4.0"),
            any(), command.capture());
        assertEquals("moment-1", command.getValue().momentName());

        when(commentService.submitComment(eq("token"), eq("idempotency"), eq("0.4.0"),
            any(), any())).thenReturn(Mono.just(new CommentService.WriteResult("post-c-1", false)));
        client.post().uri("/comments")
            .header("X-WeApp-Session", "token")
            .header("X-Idempotency-Key", "idempotency")
            .header("X-WeApp-Client-Version", "0.4.0")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"postName\":\"post-1\",\"displayName\":\"访客昵称\","
                + "\"content\":\"正文\",\"privacyConsentVersion\":\"v1\"}")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("pending")
            .jsonPath("$.commentName").isEqualTo("post-c-1")
            .jsonPath("$.requestId").isNotEmpty();

        when(commentService.submitReply(eq("token"), eq("idempotency"), eq("0.4.0"),
            any(), any())).thenReturn(Mono.just(new CommentService.WriteResult("reply-1", true)));
        client.post().uri("/comments/comment-1/replies")
            .header("X-WeApp-Session", "token")
            .header("X-Idempotency-Key", "idempotency")
            .header("X-WeApp-Client-Version", "0.4.0")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"displayName\":\"访客昵称\",\"content\":\"回复\","
                + "\"privacyConsentVersion\":\"v1\"}")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("published")
            .jsonPath("$.replyName").isEqualTo("reply-1")
            .jsonPath("$.requestId").isNotEmpty();
    }

    @Test
    void momentRouteRequiresSessionAndIdempotencyHeaders() {
        client.post().uri("/moments/moment-1/comments")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"displayName\":\"访客昵称\",\"content\":\"正文\",\"privacyConsentVersion\":\"v1\"}")
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody()
            .jsonPath("$.code").isEqualTo("SESSION_REQUIRED")
            .jsonPath("$.requestId").isNotEmpty();

        client.post().uri("/moments/moment-1/comments")
            .header("X-WeApp-Session", "token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"displayName\":\"访客昵称\",\"content\":\"正文\",\"privacyConsentVersion\":\"v1\"}")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("VALIDATION_ERROR")
            .jsonPath("$.requestId").isNotEmpty();

        when(commentService.submitMomentComment(eq("token"), eq("idempotency"), eq("0.4.0"),
            any(), any())).thenReturn(Mono.error(new ApiException(
                ErrorCode.PRIVACY_CONSENT_REQUIRED, "请先同意当前隐私政策")));
        client.post().uri("/moments/moment-1/comments")
            .header("X-WeApp-Session", "token")
            .header("X-Idempotency-Key", "idempotency")
            .header("X-WeApp-Client-Version", "0.4.0")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"displayName\":\"访客昵称\",\"content\":\"正文\",\"privacyConsentVersion\":\"old\"}")
            .exchange()
            .expectStatus().isEqualTo(428)
            .expectBody()
            .jsonPath("$.code").isEqualTo("PRIVACY_CONSENT_REQUIRED")
            .jsonPath("$.requestId").isNotEmpty()
            .jsonPath("$.retryAfter").doesNotExist();

        when(commentService.submitMomentComment(eq("token"), eq("idempotency"), eq("0.4.0"),
            any(), any())).thenReturn(Mono.error(new ApiException(
                ErrorCode.RATE_LIMITED, "操作过于频繁，请稍后再试", 30)));
        client.post().uri("/moments/moment-1/comments")
            .header("X-WeApp-Session", "token")
            .header("X-Idempotency-Key", "idempotency")
            .header("X-WeApp-Client-Version", "0.4.0")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"displayName\":\"访客昵称\",\"content\":\"正文\",\"privacyConsentVersion\":\"v1\"}")
            .exchange()
            .expectStatus().isEqualTo(429)
            .expectBody()
            .jsonPath("$.code").isEqualTo("RATE_LIMITED")
            .jsonPath("$.requestId").isNotEmpty()
            .jsonPath("$.retryAfter").isEqualTo(30);
    }

    @Test
    void articleRouteRequiresTheSameWriteHeadersAndRejectsInvalidIdempotencyLengths() {
        String body = "{\"postName\":\"post-1\",\"displayName\":\"访客昵称\","
            + "\"content\":\"正文\",\"privacyConsentVersion\":\"v1\"}";

        client.post().uri("/comments")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody()
            .jsonPath("$.code").isEqualTo("SESSION_REQUIRED")
            .jsonPath("$.requestId").isNotEmpty();

        client.post().uri("/comments")
            .header("X-WeApp-Session", "token")
            .header("X-Idempotency-Key", "short")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("VALIDATION_ERROR")
            .jsonPath("$.requestId").isNotEmpty();

        when(commentService.submitComment(eq("token"), eq("12345678"), eq("0.4.0"),
            any(), any())).thenReturn(Mono.just(new CommentService.WriteResult("article-1", true)));
        client.post().uri("/comments")
            .header("X-WeApp-Session", "token")
            .header("X-Idempotency-Key", "12345678")
            .header("X-WeApp-Client-Version", "0.4.0")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.commentName").isEqualTo("article-1")
            .jsonPath("$.retryAfter").doesNotExist();

        client.post().uri("/comments")
            .header("X-WeApp-Session", "token")
            .header("X-Idempotency-Key", "x".repeat(129))
            .header("X-WeApp-Client-Version", "0.4.0")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("VALIDATION_ERROR")
            .jsonPath("$.requestId").isNotEmpty();
    }

    @Test
    void articleAndReplyRoutesPreserveBusinessErrorStatusAndHideRetryAfter() {
        when(commentService.submitComment(eq("token"), eq("idempotency"), eq("0.4.0"),
            any(), any())).thenReturn(Mono.error(new ApiException(
                ErrorCode.COMMENT_NOT_ALLOWED, "该内容暂不支持评论")));
        client.post().uri("/comments")
            .header("X-WeApp-Session", "token")
            .header("X-Idempotency-Key", "idempotency")
            .header("X-WeApp-Client-Version", "0.4.0")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"postName\":\"post-1\",\"displayName\":\"访客昵称\","
                + "\"content\":\"正文\",\"privacyConsentVersion\":\"v1\"}")
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("COMMENT_NOT_ALLOWED")
            .jsonPath("$.requestId").isNotEmpty()
            .jsonPath("$.retryAfter").doesNotExist();

        when(commentService.submitReply(eq("token"), eq("idempotency"), eq("0.4.0"),
            any(), any())).thenReturn(Mono.error(new ApiException(
                ErrorCode.CLIENT_UPDATE_REQUIRED, "请更新小程序后再评论")));
        client.post().uri("/comments/comment-1/replies")
            .header("X-WeApp-Session", "token")
            .header("X-Idempotency-Key", "idempotency")
            .header("X-WeApp-Client-Version", "0.4.0")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"displayName\":\"访客昵称\",\"content\":\"回复\","
                + "\"privacyConsentVersion\":\"v1\"}")
            .exchange()
            .expectStatus().isEqualTo(426)
            .expectBody()
            .jsonPath("$.code").isEqualTo("CLIENT_UPDATE_REQUIRED")
            .jsonPath("$.requestId").isNotEmpty()
            .jsonPath("$.retryAfter").doesNotExist();
    }

    @Test
    void emptyBodiesAndMalformedJsonRemainValidationErrorsForEveryWriteRoute() {
        client.post().uri("/comments")
            .header("X-WeApp-Session", "token")
            .header("X-Idempotency-Key", "idempotency")
            .contentType(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("VALIDATION_ERROR")
            .jsonPath("$.requestId").isNotEmpty();

        client.post().uri("/comments/comment-1/replies")
            .header("X-WeApp-Session", "token")
            .header("X-Idempotency-Key", "idempotency")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"displayName\":")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("VALIDATION_ERROR")
            .jsonPath("$.requestId").isNotEmpty();
    }
}
