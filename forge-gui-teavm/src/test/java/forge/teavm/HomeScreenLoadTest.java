package forge.teavm;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ConsoleMessage;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Automated smoke test that verifies the compiled Forge TeaVM web app reaches
 * the home screen without fatal errors.
 *
 * <h2>Prerequisites</h2>
 * <p>This test requires the TeaVM build to have been run first:
 * <pre>
 *   mvn -pl forge-gui-teavm -Pteavm package
 * </pre>
 * If the compiled output is absent the test is automatically skipped rather
 * than failing, so it never breaks a plain {@code mvn test} run.
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>An in-process JDK {@link HttpServer} serves the compiled webapp
 *       directory over HTTP on a random port.</li>
 *   <li>A headless Chromium browser (managed by Playwright Java) loads
 *       {@code http://localhost:PORT/}.</li>
 *   <li>The test waits up to {@value #LOAD_TIMEOUT_MS} ms for a JS signal
 *       that indicates the Forge home screen has rendered.</li>
 *   <li>All browser console messages are captured; the test asserts that no
 *       uncaught JavaScript errors occurred during startup.</li>
 * </ol>
 *
 * <h2>The readiness signal</h2>
 * <p>{@link TeaVMLauncher} sets {@code window.__forgeReady = true} once the
 * first render frame after {@code Forge.afterDBloaded} is received.
 * Playwright polls this flag every 500 ms until it becomes truthy or the
 * timeout expires.
 *
 * <h2>CI setup</h2>
 * <p>Playwright downloads its own Chromium binary on first use.  In CI add:
 * <pre>
 *   mvn exec:java -Dexec.mainClass="com.microsoft.playwright.CLI" \
 *                 -Dexec.args="install chromium" \
 *                 -pl forge-gui-teavm
 * </pre>
 * or use the {@code playwright install} approach from the Node CLI.
 */
@Test(groups = {"teavm-smoke"})
public class HomeScreenLoadTest {

    /** Path to the compiled webapp, relative to the module's working directory. */
    private static final String WEBAPP_DIR = "target/dist/webapp";

    /** Flag set in browser JS by the app when the home screen is ready. */
    private static final String READY_FLAG = "window.__forgeReady";

    /** Maximum time (ms) to wait for the ready flag before failing. */
    private static final int LOAD_TIMEOUT_MS = 90_000;

    /** Poll interval (ms) when waiting for the ready flag. */
    private static final int POLL_INTERVAL_MS = 500;

    private HttpServer httpServer;
    private int serverPort;
    private Playwright playwright;
    private Browser browser;
    private final List<String> consoleErrors = new ArrayList<>();

    @BeforeClass
    public void setUp() throws IOException {
        Path webappPath = Paths.get(WEBAPP_DIR);
        if (!Files.exists(webappPath.resolve("index.html"))) {
            throw new SkipException(
                "TeaVM build output not found at " + webappPath.toAbsolutePath()
                + " – run 'mvn -Pteavm package' first.");
        }

        httpServer = startFileServer(webappPath);
        serverPort = httpServer.getAddress().getPort();

        playwright = Playwright.create();
        browser = playwright.chromium().launch(
            new BrowserType.LaunchOptions()
                .setHeadless(true)
                .addArgs("--disable-gpu")           // software rendering in CI
                .addArgs("--no-sandbox")             // required in many CI envs
                .addArgs("--disable-dev-shm-usage")); // avoids /dev/shm exhaustion
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    /**
     * Verifies that the Forge TeaVM app loads without fatal errors and reaches
     * the home screen.
     */
    @Test
    public void homeScreenLoads() throws InterruptedException {
        BrowserContext ctx = browser.newContext();
        Page page = ctx.newPage();

        // Capture all console messages so we can assert on them after load.
        List<ConsoleMessage> consoleMsgs = new ArrayList<>();
        AtomicBoolean fatalErrorSeen = new AtomicBoolean(false);

        page.onConsoleMessage(msg -> {
            consoleMsgs.add(msg);
            String text = msg.text();
            // WebApplication.onError() groups fatal errors starting with "Fatal Error:"
            if (msg.type().equals("error") && (
                    text.contains("Fatal Error") ||
                    text.contains("Uncaught") ||
                    text.contains("Exception"))) {
                fatalErrorSeen.set(true);
            }
        });

        page.onPageError(err -> {
            consoleErrors.add("Page error: " + err);
            fatalErrorSeen.set(true);
        });

        page.navigate("http://localhost:" + serverPort + "/");

        // Wait for the ready flag to be set by TeaVMLauncher, polling periodically.
        boolean ready = waitForReadyFlag(page);

        // Print collected messages for diagnostics regardless of outcome.
        System.out.println("=== Browser console (" + consoleMsgs.size() + " messages) ===");
        for (ConsoleMessage msg : consoleMsgs) {
            System.out.printf("[%s] %s%n", msg.type(), msg.text());
        }
        System.out.println("==============================================");

        Assert.assertFalse(fatalErrorSeen.get(),
            "A fatal JavaScript error was detected in the browser console.");
        Assert.assertTrue(ready,
            "Forge home screen did not signal readiness within "
            + LOAD_TIMEOUT_MS + " ms.  See console output above for details.");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Polls {@link #READY_FLAG} in the browser page every
     * {@link #POLL_INTERVAL_MS} milliseconds until it becomes {@code true} or
     * {@link #LOAD_TIMEOUT_MS} elapses.
     */
    private boolean waitForReadyFlag(Page page) throws InterruptedException {
        long deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Object result = page.evaluate("() => !!(" + READY_FLAG + ")");
            if (Boolean.TRUE.equals(result)) {
                return true;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return false;
    }

    /**
     * Starts a minimal JDK {@link HttpServer} that serves static files from
     * {@code webappPath}.  Using the JDK built-in server avoids adding
     * Jetty as a test dependency (Jetty is already pulled in by
     * {@code backend-web} but only in compile scope).
     */
    private static HttpServer startFileServer(Path webappPath) throws IOException {
        // Bind to an OS-chosen ephemeral port (0 = any free port).
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String uriPath = exchange.getRequestURI().getPath();
            // Strip leading slash; serve index.html for bare "/".
            if (uriPath.equals("/")) {
                uriPath = "/index.html";
            }
            Path filePath = webappPath.resolve(uriPath.substring(1)).normalize();
            // Prevent path traversal above the webapp root.
            if (!filePath.startsWith(webappPath)) {
                exchange.sendResponseHeaders(403, 0);
                exchange.getResponseBody().close();
                return;
            }
            if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
                byte[] body = "404 Not Found".getBytes();
                exchange.sendResponseHeaders(404, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
                return;
            }
            String contentType = guessContentType(filePath.toString());
            byte[] bytes = Files.readAllBytes(filePath);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.setExecutor(null); // use default executor
        server.start();
        return server;
    }

    private static String guessContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js"))   return "application/javascript";
        if (path.endsWith(".wasm")) return "application/wasm";
        if (path.endsWith(".css"))  return "text/css";
        if (path.endsWith(".png"))  return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }
}
