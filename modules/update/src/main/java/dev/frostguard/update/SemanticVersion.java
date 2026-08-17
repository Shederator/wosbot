package dev.frostguard.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SemanticVersion implements Comparable<SemanticVersion> {
    private static final Pattern FORMAT = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");

    private final String value;
    private final int major;
    private final int minor;
    private final int patch;
    private final List<String> prerelease;

    private SemanticVersion(String value, int major, int minor, int patch, List<String> prerelease) {
        this.value = value;
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = List.copyOf(prerelease);
    }

    public static SemanticVersion parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Semantic version is required");
        }
        Matcher matcher = FORMAT.matcher(value.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid semantic version: " + value);
        }
        List<String> prerelease = new ArrayList<>();
        if (matcher.group(4) != null) {
            for (String identifier : matcher.group(4).split("\\.")) {
                if (identifier.matches("\\d+") && identifier.length() > 1 && identifier.startsWith("0")) {
                    throw new IllegalArgumentException(
                            "Numeric prerelease identifiers cannot contain leading zeroes: " + value);
                }
                prerelease.add(identifier);
            }
        }
        return new SemanticVersion(value.trim(), Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)), prerelease);
    }

    @Override
    public int compareTo(SemanticVersion other) {
        int result = Integer.compare(major, other.major);
        if (result == 0) result = Integer.compare(minor, other.minor);
        if (result == 0) result = Integer.compare(patch, other.patch);
        if (result != 0) return result;
        if (prerelease.isEmpty()) return other.prerelease.isEmpty() ? 0 : 1;
        if (other.prerelease.isEmpty()) return -1;
        int count = Math.max(prerelease.size(), other.prerelease.size());
        for (int index = 0; index < count; index++) {
            if (index >= prerelease.size()) return -1;
            if (index >= other.prerelease.size()) return 1;
            result = compareIdentifier(prerelease.get(index), other.prerelease.get(index));
            if (result != 0) return result;
        }
        return 0;
    }

    private static int compareIdentifier(String left, String right) {
        boolean leftNumeric = left.matches("\\d+");
        boolean rightNumeric = right.matches("\\d+");
        if (leftNumeric && rightNumeric) {
            return new java.math.BigInteger(left).compareTo(new java.math.BigInteger(right));
        }
        if (leftNumeric != rightNumeric) {
            return leftNumeric ? -1 : 1;
        }
        return left.compareTo(right);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof SemanticVersion other && compareTo(other) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch, prerelease);
    }

    @Override
    public String toString() {
        return value;
    }
}
