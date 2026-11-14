package com.github.catvod.utils;

import java.net.URI;

public final class UriUtil {
    public static String resolve(String base, String ref) {
        try {
            return URI.create(base).resolve(ref).toString();
        } catch (Exception e) {
            return ref;
        }
    }
}
