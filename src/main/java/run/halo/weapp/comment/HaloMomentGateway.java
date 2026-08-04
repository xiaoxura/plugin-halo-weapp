package run.halo.weapp.comment;

import reactor.core.publisher.Mono;

/**
 * Validates a Moment through its optional Public API without depending on the
 * optional plugin's Java classes.
 */
public interface HaloMomentGateway {

    /** Completes only when the Moment exists, is public, approved, and active. */
    Mono<Void> validateCommentable(String momentName);
}
