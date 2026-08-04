package run.halo.weapp.comment;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Server-generated subject reference for a comment write.
 *
 * <p>The client never supplies a group, kind, or version.  Keeping this as a
 * small value object makes it harder for a future route to accidentally pass
 * through an arbitrary Halo GVK.</p>
 */
public record CommentSubject(String group, String kind, String version, String name) {

    private static final Pattern RESOURCE_NAME =
        Pattern.compile("^[A-Za-z0-9][A-Za-z0-9.-]{0,127}$");

    public CommentSubject {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(version, "version");
        if (!isSupported(group, kind, version)) {
            throw new IllegalArgumentException("unsupported subject type");
        }
        if (name == null || !RESOURCE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid subject name");
        }
    }

    private static boolean isSupported(String group, String kind, String version) {
        return ("content.halo.run".equals(group) && "Post".equals(kind)
                && "v1alpha1".equals(version))
            || ("moment.halo.run".equals(group) && "Moment".equals(kind)
                && "v1alpha1".equals(version));
    }

    public static CommentSubject post(String name) {
        return new CommentSubject("content.halo.run", "Post", "v1alpha1", name);
    }

    public static CommentSubject moment(String name) {
        return new CommentSubject("moment.halo.run", "Moment", "v1alpha1", name);
    }

    public boolean isPost() {
        return "content.halo.run".equals(group) && "Post".equals(kind)
            && "v1alpha1".equals(version);
    }

    public boolean isMoment() {
        return "moment.halo.run".equals(group) && "Moment".equals(kind)
            && "v1alpha1".equals(version);
    }
}
