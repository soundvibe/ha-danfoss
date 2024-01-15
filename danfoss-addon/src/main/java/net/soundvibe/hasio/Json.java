package net.soundvibe.hasio;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Json {

    public static final Gson GSON = new Gson();

    public static <T> T fromPath(Path path, Class<T> classz) {
        try {
            var jsonString = Files.readString(path);
            return GSON.fromJson(jsonString, classz);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static <T> T fromString(String jsonString, Class<T> classz) {
        return GSON.fromJson(jsonString, classz);
    }

    public static String toJsonString(Object o) {
        return GSON.toJson(o);
    }

}
