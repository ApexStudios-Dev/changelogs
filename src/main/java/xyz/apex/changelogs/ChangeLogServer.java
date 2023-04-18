package xyz.apex.changelogs;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import fi.iki.elonen.NanoHTTPD;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.util.PathConverter;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static fi.iki.elonen.NanoHTTPD.Response.Status.*;
import static java.util.Arrays.asList;

/**
 * Created by covers1624 on 18/4/23.
 */
public class ChangeLogServer extends NanoHTTPD {

    public static final Logger LOGGER = LogManager.getLogger();

    private final String secret;
    public final Path dir;
    private final boolean quiet;

    public ChangeLogServer(String hostname, int port, String secret, Path dir, boolean quiet) {
        super(hostname, port);
        this.secret = secret;
        this.dir = dir;
        this.quiet = quiet;
    }

    public static void main(String[] args) throws Throwable {
        OptionParser parser = new OptionParser();
        OptionSpec<String> nonOptions = parser.nonOptions();

        OptionSpec<Void> helpOpt = parser.acceptsAll(asList("h", "help"), "Prints this help").forHelp();

        OptionSpec<String> listenAddressOpt = parser.acceptsAll(asList("a", "address"), "The address to bind to.")
                .withRequiredArg()
                .defaultsTo("0.0.0.0");

        OptionSpec<Integer> bindPortOpt = parser.acceptsAll(asList("p", "port"), "The port to bind to.")
                .withRequiredArg()
                .ofType(Integer.class)
                .defaultsTo(8080);

        OptionSpec<Void> quietOpt = parser.acceptsAll(asList("q", "quiet"), "Disable access logging.");

        OptionSpec<String> secretOpt = parser.acceptsAll(asList("s", "secret"), "The secret key to access the api.")
                .withRequiredArg()
                .required();

        OptionSpec<Path> dirOpt = parser.acceptsAll(asList("d", "directory"), "The folder to store data in.")
                .withRequiredArg()
                .required()
                .withValuesConvertedBy(new PathConverter());

        OptionSet optSet = parser.parse(args);
        if (optSet.has(helpOpt)) {
            parser.printHelpOn(System.err);
            System.exit(-1);
        }

        String listenAddress = optSet.valueOf(listenAddressOpt);
        int bindPort = optSet.valueOf(bindPortOpt);
        String secret = optSet.valueOf(secretOpt);
        Path dir = optSet.valueOf(dirOpt);

        if (Files.notExists(dir)) {
            Files.createDirectories(dir);
        }

        if (!Files.isDirectory(dir)) {
            System.err.println("Expected '--directory' path to be a directory.");
            parser.printHelpOn(System.err);
            System.exit(-1);
        }

        LOGGER.info("Starting Changelog Server.");

        ChangeLogServer server = new ChangeLogServer(listenAddress, bindPort, secret, dir, optSet.has(quietOpt));
        // Use a proper thread pool instead of making a new thread for each request.
        server.setAsyncRunner(new AsyncRunner() {

            // TODO perhaps a fixed size thread pool?
            private final ExecutorService EXECUTOR = Executors.newCachedThreadPool(new ThreadFactoryBuilder().setNameFormat("HTTP Thread #%2d").setDaemon(true).build());

            @Override
            public void closeAll() {
                EXECUTOR.shutdown();
            }

            @Override
            public void closed(ClientHandler clientHandler) {
            }

            @Override
            public void exec(ClientHandler code) {
                EXECUTOR.submit(code);
            }
        });
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        LOGGER.info("Listening on {}:{}", server.getHostname(), server.getListeningPort());
    }

    @Override
    public Response serve(IHTTPSession session) {
        Throwable ex = null;
        Response response;
        try {
            response = serveImpl(session);

            // CORS hax, go away chrome, you can do what you want, I don't care.
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Access-Control-Allow-Methods", "OPTIONS, PUT, GET");
            response.addHeader("Access-Control-Allow-Headers", session.getHeaders().get("access-control-request-headers"));
            response.addHeader("Access-Control-Max-Age", "3628800");
        } catch (Throwable e) {
            ex = e;
            response = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "There was an internal error processing this request.");
        }
        logRequest(session, response, ex);
        return response;
    }

    private void logRequest(IHTTPSession session, Response response, @Nullable Throwable ex) {
        // Only log if quiet is false, or we have an exception.
        if (quiet && ex == null) return;
        Level level = ex == null ? Level.INFO : Level.ERROR;

        LOGGER.log(level, "{} {} {} - {} {} {}",
                session.getHeaders().getOrDefault("x-fowarded-for", session.getRemoteIpAddress()),
                session.getMethod(),
                session.getUri(),
                response.getStatus(),
                availableBytes(response.getData()),
                session.getHeaders().getOrDefault("user-agent", "?")
        );
        if (ex != null) {
            LOGGER.log(level, ex);
        }
    }

    private Response serveImpl(IHTTPSession session) throws Throwable {
        // Whatever go away OPTIONS.
        if (session.getMethod() == Method.OPTIONS) {
            return newFixedLengthResponse(NO_CONTENT, null, null);
        }

        String effectiveUri = session.getUri();
        if (effectiveUri.startsWith("/")) {
            effectiveUri = effectiveUri.substring(1);
        }

        if (effectiveUri.isEmpty()) {
            return newFixedLengthResponse(NOT_FOUND, null, null);
        }

        if (effectiveUri.endsWith("/")) {
            effectiveUri = effectiveUri.substring(0, effectiveUri.length() - 1);
        }
        String[] segs = effectiveUri.split("/");
        if (segs.length > 2) {
            return newFixedLengthResponse(BAD_REQUEST, null, null);
        }

        String mod = segs[0];
        String version = segs.length == 2 ? segs[1] : null;

        return switch (session.getMethod()) {
            case GET -> getResponse(mod, version);
            case PUT -> putResponse(session, mod, version);
            default -> newFixedLengthResponse(BAD_REQUEST, null, null);
        };
    }

    @NotNull
    private Response getResponse(String mod, @Nullable String version) throws IOException {
        Path modFolder = dir.resolve(mod);
        if (Files.notExists(modFolder)) {
            return newFixedLengthResponse(NOT_FOUND, null, null);
        }

        if (version == null) {
            try (Stream<Path> files = Files.list(modFolder)) {
                version = files.map(e -> e.getFileName().toString().replace(".txt", ""))
                        .max(Comparator.comparing(ComparableVersion::new))
                        .orElse(null);
            }
        }
        if (version == null) {
            return newFixedLengthResponse(NOT_FOUND, null, null);
        }
        return returnFile(modFolder.resolve(version + ".txt"));
    }

    private static Response returnFile(Path file) throws IOException {
        if (!Files.exists(file)) {
            return newFixedLengthResponse(NOT_FOUND, null, null);
        }
        return newFixedLengthResponse(OK, "text/plain", Files.newInputStream(file), Files.size(file));
    }

    private Response putResponse(IHTTPSession session, String mod, String version) throws Throwable {
        if (!secret.equals(session.getHeaders().get("x-api-key"))) {
            return newFixedLengthResponse(UNAUTHORIZED, null, null);
        }

        if(version == null || version.isBlank()) {
            return newFixedLengthResponse(BAD_REQUEST, null, null);
        }

        Path file = dir.resolve(mod).resolve(version + ".txt");
        if (Files.notExists(file.getParent())) {
            Files.createDirectories(file.getParent());
        }

        String body = new String(getBody(session), StandardCharsets.UTF_8);
        if (!body.endsWith("\n")) {
            body += "\n";
        }

        Files.writeString(file, body);
        return newFixedLengthResponse(NO_CONTENT, null, null);
    }

    private static String availableBytes(@Nullable InputStream is) {
        if (is != null) {
            try {
                return String.valueOf(is.available());
            } catch (IOException ignored) {
            }
        }
        return "?";
    }

    private static byte[] getBody(IHTTPSession session) throws IOException {
        String lenStr = session.getHeaders().get("content-length");
        if (lenStr == null) return new byte[0];
        int len = Integer.parseInt(lenStr);
        if (len == 0) return new byte[0];
        return session.getInputStream().readNBytes(len);
    }
}
