package net.soundvibe.hasio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JsonMapper;
import net.soundvibe.hasio.danfoss.Bootstrapper;
import net.soundvibe.hasio.danfoss.protocol.DanfossDiscovery;
import net.soundvibe.hasio.danfoss.protocol.config.AppConfig;
import net.soundvibe.hasio.danfoss.protocol.config.DanfossBindingConfig;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

public class Application {

    private static final Logger logger = LoggerFactory.getLogger(Application.class);

    public static final Path CONFIG_FILE = Paths.get("/share/danfoss-icon/danfoss_config.json");
    public static final List<Path> CONFIG_FILES = List.of(CONFIG_FILE, Paths.get("danfoss_config.json"));
    public static void main(String[] args) throws IOException {
        logger.info("starting danfoss icon addon...");
        try {
            Files.createDirectories(Paths.get("/share/danfoss-icon/"));
        } catch (Exception e) {
            logger.error(e.toString());
        }
        var gson = new GsonBuilder().create();
        var gsonMapper = new JsonMapper() {
            @Override
            public @NotNull String toJsonString(@NotNull Object obj, @NotNull Type type) {
                return gson.toJson(obj, type);
            }

            @Override
            public <T> @NotNull T fromJsonString(@NotNull String json, @NotNull Type targetType) {
                return gson.fromJson(json, targetType);
            }
        };

        var app = Javalin.create(config -> {
            config.staticFiles.add("/public", Location.CLASSPATH);
            config.jsonMapper(gsonMapper);
        });
        app.get("/health", ctx -> ctx.result("OK"));
        app.post("/discover", ctx -> {
            var bindingConfig = DanfossBindingConfig.create(ctx.formParam("userName"));
            try (var discovery = new DanfossDiscovery(ctx.formParam("oneTimeCode"), bindingConfig)) {
                var response = discovery.discover();
                if (response.housePeerId != null) {
                    // persist
                    var appConfig = new AppConfig(bindingConfig.privateKey(), bindingConfig.userName(), response.housePeerId);
                    var appConfigJson = new Gson().toJson(appConfig);
                    Files.writeString(CONFIG_FILE, appConfigJson);
                    ctx.html(String.format("Discovered Icon house %s with %s peerId (privateKey: %s) successfully",
                            response.houseName, response.housePeerId, Arrays.toString(bindingConfig.privateKey())));
                    var bootstrapper =  new Bootstrapper(appConfig);
                    bootstrapper.bootstrap(app);
                } else {
                    ctx.html("House was not discovered");
                }
            }
        });

        CONFIG_FILES.stream()
                .filter(Files::exists)
                .map(path -> {
                    try {
                        return Files.readString(path);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .limit(1)
                .findAny()
                .ifPresentOrElse(json -> {
                    logger.info("config file found, bootstrapping...");
                    var appConfig = gson.fromJson(json, AppConfig.class);
                    var bootstrapper =  new Bootstrapper(appConfig);
                    bootstrapper.bootstrap(app);
                }, () -> {
                    logger.info("config file not found, use ip:port/discover endpoint to discover new house");
                });


        app.start(9199);
        logger.info("started addon");
    }
}
