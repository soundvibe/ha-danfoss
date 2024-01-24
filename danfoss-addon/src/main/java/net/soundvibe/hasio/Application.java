package net.soundvibe.hasio;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JsonMapper;
import net.soundvibe.hasio.danfoss.protocol.DanfossDiscovery;
import net.soundvibe.hasio.danfoss.protocol.config.AppConfig;
import net.soundvibe.hasio.danfoss.protocol.config.DanfossBindingConfig;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static net.soundvibe.hasio.Json.GSON;

public class Application {

    private static final Logger logger = LoggerFactory.getLogger(Application.class);

    public static final Path DANFOSS_CONFIG_FILE = Paths.get("/share/danfoss-icon/danfoss_config.json");
    public static final Path DANFOSS_CONFIG_FILE_LOCAL = Paths.get("danfoss_config.json");
    public static final Path DANFOSS_CONFIG_DIR = Paths.get("/share/danfoss-icon");
    public static final Path ADDON_CONFIG_FILE = Paths.get("/data/options.json");
    public static final List<Path> CONFIG_FILES = List.of(DANFOSS_CONFIG_FILE, DANFOSS_CONFIG_FILE_LOCAL);
    public static void main(String[] args) {
        logger.info("starting danfoss icon addon...");

        var app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.staticFiles.add("/public", Location.CLASSPATH);
            var gsonMapper = new JsonMapper() {
                @Override
                public @NotNull String toJsonString(@NotNull Object obj, @NotNull Type type) {
                    return GSON.toJson(obj, type);
                }

                @Override
                public <T> @NotNull T fromJsonString(@NotNull String json, @NotNull Type targetType) {
                    return GSON.fromJson(json, targetType);
                }
            };
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
                    var appConfigJson = Json.toJsonString(appConfig);
                    ctx.html(STR."""
      <h1>Discovered Icon house <b>\{response.houseName}</b> successfully</h1>
      <p>Write the following config to <i>/share/danfoss-icon/danfoss_config.json</i> if addon won't start properly</p>
      <h3>danfoss_config.json</h3>
      <p style="background:#9FE2BF">
      {</br>
      &nbsp;&nbsp;"privateKey": \{ Arrays.toString(appConfig.privateKey()) },</br>
      &nbsp;&nbsp;"userName": "\{ appConfig.userName() }",</br>
      &nbsp;&nbsp;"peerId": "\{ appConfig.peerId() }"</br>
      }</br>
      </p>
      """);
                    logger.info("Discovered Icon house {} with {} peerId (privateKey: {}) successfully",
                            response.houseName, response.housePeerId, Arrays.toString(bindingConfig.privateKey()));
                    Files.createDirectories(DANFOSS_CONFIG_DIR);
                    Files.writeString(DANFOSS_CONFIG_FILE, appConfigJson);
                    discovery.close();
                    var bootstrapper =  new Bootstrapper(appConfig);
                    bootstrapper.bootstrap(app);
                } else {
                    ctx.html("House was not discovered");
                }
            } catch (Throwable e) {
                logger.error("Failed to discover a new house", e);
                ctx.html(String.format("House was not discovered because of an error: %s", e.getMessage())).status(500);
            }
        });

        CONFIG_FILES.stream()
                .filter(Files::exists)
                .map(path -> Json.fromPath(path, AppConfig.class))
                .limit(1)
                .findAny()
                .ifPresentOrElse(appConfig -> {
                    logger.info("config file found, bootstrapping...");
                    var bootstrapper =  new Bootstrapper(appConfig);
                    bootstrapper.bootstrap(app);
                }, () -> logger.info("config file not found, use ip:port/discover endpoint to discover new house"));


        app.start(9199);
        logger.info("started addon");
    }
}
