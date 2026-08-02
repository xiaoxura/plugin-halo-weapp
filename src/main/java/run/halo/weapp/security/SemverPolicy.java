package run.halo.weapp.security;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 最小 SemVer 比较器，遵循 semver.org 的核心版本与预发布排序规则。 */
public final class SemverPolicy {

    private static final Pattern PATTERN = Pattern.compile(
        "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
            + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
            + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");

    private SemverPolicy() {
    }

    /** 返回 left 与 right 的比较值；任一非法时返回 null。 */
    public static Integer compare(String left, String right) {
        Version a = parse(left);
        Version b = parse(right);
        if (a == null || b == null) {
            return null;
        }
        int core = Long.compare(a.major, b.major);
        if (core == 0) core = Long.compare(a.minor, b.minor);
        if (core == 0) core = Long.compare(a.patch, b.patch);
        if (core != 0) return core;
        if (a.pre.isEmpty() && b.pre.isEmpty()) return 0;
        if (a.pre.isEmpty()) return 1;
        if (b.pre.isEmpty()) return -1;
        int max = Math.max(a.pre.size(), b.pre.size());
        for (int i = 0; i < max; i++) {
            if (i >= a.pre.size()) return -1;
            if (i >= b.pre.size()) return 1;
            String x = a.pre.get(i);
            String y = b.pre.get(i);
            boolean xNumeric = x.chars().allMatch(Character::isDigit);
            boolean yNumeric = y.chars().allMatch(Character::isDigit);
            int compared;
            if (xNumeric && yNumeric) {
                compared = compareNumericIdentifier(x, y);
            } else if (xNumeric != yNumeric) {
                compared = xNumeric ? -1 : 1;
            } else {
                compared = x.compareTo(y);
            }
            if (compared != 0) return compared;
        }
        return 0;
    }

    private static Version parse(String value) {
        if (value == null) return null;
        Matcher matcher = PATTERN.matcher(value.trim());
        if (!matcher.matches()) return null;
        try {
            long major = Long.parseLong(matcher.group(1));
            long minor = Long.parseLong(matcher.group(2));
            long patch = Long.parseLong(matcher.group(3));
            List<String> pre = new ArrayList<>();
            if (matcher.group(4) != null) {
                for (String part : matcher.group(4).split("\\.")) {
                    // SemVer 的纯数字预发布标识禁止前导零。
                    if (part.length() > 1 && part.charAt(0) == '0'
                        && part.chars().allMatch(Character::isDigit)) {
                        return null;
                    }
                    pre.add(part);
                }
            }
            return new Version(major, minor, patch, pre);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int compareNumericIdentifier(String left, String right) {
        if (left.length() != right.length()) {
            return Integer.compare(left.length(), right.length());
        }
        return left.compareTo(right);
    }

    private record Version(long major, long minor, long patch, List<String> pre) {
    }
}
