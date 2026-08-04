package run.halo.weapp.comment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CommentSubjectTest {

    @Test
    void factoriesCreateOnlySupportedSubjects() {
        CommentSubject post = CommentSubject.post("post-1");
        CommentSubject moment = CommentSubject.moment("moment-1");

        assertEquals("content.halo.run", post.group());
        assertEquals("Post", post.kind());
        assertEquals("moment.halo.run", moment.group());
        assertEquals("Moment", moment.kind());
    }

    @Test
    void directConstructionRejectsUnsupportedGvk() {
        assertThrows(IllegalArgumentException.class,
            () -> new CommentSubject("evil.example", "Post", "v1alpha1", "post-1"));
        assertThrows(IllegalArgumentException.class,
            () -> new CommentSubject("content.halo.run", "Page", "v1alpha1", "page-1"));
    }

    @Test
    void supportedSubjectsStillRejectInvalidNames() {
        assertThrows(IllegalArgumentException.class, () -> CommentSubject.post("../escape"));
        assertThrows(IllegalArgumentException.class, () -> CommentSubject.moment(" "));
    }
}
