package dev.amogh.seats.show;

import java.util.UUID;
import java.util.regex.Pattern;

/** Show ids are lowercase UUID strings; anything else can't exist. */
public final class ShowIds {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private ShowIds() {
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public static boolean isValid(String id) {
        return id != null && UUID_PATTERN.matcher(id).matches();
    }
}
